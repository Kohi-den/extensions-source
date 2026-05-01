package eu.kanade.tachiyomi.animeextension.en.hanime

import android.util.Log
import com.dylibso.chicory.runtime.HostFunction
import com.dylibso.chicory.runtime.Instance
import com.dylibso.chicory.wasm.types.ValueType
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Chicory-based WASM glue layer for hanime.tv signature generation.
 *
 * Provides 25 host function imports that the emscripten-compiled WASM binary
 * expects from module "a". Each import becomes a Chicory [HostFunction] that
 * either no-ops, stubs, or performs real work (ASM_CONSTS bridge, heap resize,
 * event listener registration, emval environment mock).
 *
 * The mission-critical imports are:
 * - **g** (_emscripten_asm_const_int): the JS↔WASM bridge through which the
 *   binary communicates signatures and timestamps via the ASM_CONSTS table.
 * - **y** (window_on): registers event listeners; the interpreter dispatches
 *   events to trigger signature computation.
 * - **p** (__emval_get_global): resolves global names to emval handles.
 * - **l** (__emval_get_property): resolves object properties by name.
 * - **m** (__emval_new_cstring): creates emval string handles from C strings.
 * - **c** (__emval_decref): releases emval handle references.
 *
 * The emval environment mock provides the WASM binary with a simulated
 * `window` object graph (window → location → origin = "https://hanime.tv")
 * that the signature computation depends on.
 */
class ChicoryGlue {

    @Volatile var capturedSignature: String? = null
        private set

    @Volatile var capturedTimestamp: Long? = null
        private set

    private val registeredEventTypes: MutableSet<String> = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    /** Immutable snapshot of event types registered via import "y" (window_on). */
    val eventTypes: Set<String> get() = registeredEventTypes.toSet()

    /** Emval handle manager — provides the mock JS environment for the WASM binary. */
    val emvalManager = EmvalHandleManager()

    // ASM_CONSTS dispatch table — maps integer IDs to handler lambdas.
    // Each handler receives a LongArray of parsed arguments (matching JS readEmAsmArgs)
    // and the Instance, returning a Long result.
    private val asmConsts = ConcurrentHashMap<Int, (LongArray, Instance) -> Long>()

    init {
        // ID 17392: Returns current Unix timestamp in seconds
        // JS: () => parseInt(new Date().getTime() / 1e3)
        asmConsts[ASM_CONST_TIMESTAMP] = { _, _ ->
            System.currentTimeMillis() / 1000L
        }

        // ID 17442: Captures signature string + timestamp from WASM
        // JS: ($0, $1) => { window.ssignature = UTF8ToString($0); window.stime = $1; }
        // $0 = pointer to signature string, $1 = timestamp integer
        asmConsts[ASM_CONST_SIGNATURE] = { args, instance ->
            val sigPtr = args[0].toInt()
            val timestamp = if (args.size > 1) args[1] else 0L
            capturedSignature = instance.memory().readCString(sigPtr)
            capturedTimestamp = timestamp
            Log.d(TAG, "ASM_CONST_SIGNATURE: captured signature (length=${capturedSignature?.length ?: 0}), timestamp=$timestamp")

            0L
        }
    }

    /**
     * Emval (Emscripten value) handle manager that simulates the JS environment
     * the WASM binary expects during signature computation.
     *
     * Emscripten's emval system maps JS values to integer handles that WASM code
     * can pass around. This manager provides a simulated window/location/document
     * object graph so that `window.location.origin` resolves to "https://hanime.tv".
     *
     * Handle conventions follow emscripten's protocol:
     * - Handle 0 = undefined (by emscripten convention)
     * - Handle 1+ = allocated values
     *
     * Thread-safe via [ConcurrentHashMap] and [AtomicInteger].
     */
    class EmvalHandleManager {

        /** Sealed representation of a JS-like value in the emval system. */
        sealed class EmvalValue {
            /** A JS object with named properties (each property maps to an emval handle). */
            data class JsObject(val properties: MutableMap<String, Int> = ConcurrentHashMap()) : EmvalValue()

            /** A JS string value. */
            data class JsString(val value: String) : EmvalValue()

            /** A JS number value (integer or floating-point). */
            data class JsNumber(val value: Double) : EmvalValue()

            /** A JS boolean value. */
            data class JsBoolean(val value: Boolean) : EmvalValue()

            /** JS undefined singleton. */
            object JsUndefined : EmvalValue()

            /** JS null singleton. */
            object JsNull : EmvalValue()

            /** A JS function placeholder. */
            data class JsFunction(val name: String, val invoker: ((List<Long>) -> Long)? = null) : EmvalValue()
        }

        private val handles = ConcurrentHashMap<Int, EmvalValue>()
        private val nextHandle = AtomicInteger(10) // Reserve 0–9 for special values

        /** Handles allocated for short-lived lookups (e.g. `.length` JsNumber) that must be released on reset. */
        private val transientHandles: MutableSet<Int> = java.util.Collections.newSetFromMap(ConcurrentHashMap<Int, Boolean>())

        /** Mark a handle as transient — it will be released by [releaseTransients] without clearing the entire environment. */
        fun markTransient(handle: Int) {
            transientHandles.add(handle)
        }

        /** Release all transient handles and clear the transient set. */
        fun releaseTransients() {
            transientHandles.forEach { handles.remove(it) }
            transientHandles.clear()
        }

        /** Pre-allocated handles for well-known JS objects. */
        var windowHandle: Int = 0
            private set
        var documentHandle: Int = 0
            private set
        var navigatorHandle: Int = 0
            private set
        var consoleHandle: Int = 0
            private set
        var locationHandle: Int = 0
            private set

        init {
            initializeMockEnvironment()
        }

        /**
         * Allocate a new handle for the given [EmvalValue].
         * @return The integer handle assigned to this value.
         */
        fun allocate(value: EmvalValue): Int {
            if (value is EmvalValue.JsUndefined) return 0
            val handle = nextHandle.getAndIncrement()
            handles[handle] = value
            return handle
        }

        /** Retrieve the [EmvalValue] for a given handle, or null if not found. */
        fun get(handle: Int): EmvalValue? {
            if (handle == 0) return EmvalValue.JsUndefined
            return handles[handle]
        }

        /** Release a handle, removing it from the table. */
        fun release(handle: Int) {
            handles.remove(handle)
        }

        /** Clear all handles and re-initialize the mock environment. */
        fun clear() {
            handles.clear()
            nextHandle.set(10)
            initializeMockEnvironment()
        }

        /**
         * Set up the mock JS environment that the WASM binary queries
         * during signature computation.
         *
         * The critical call chain is:
         * `__emval_get_global("window")` → `__emval_get_property(handle, "top")`
         * → `__emval_get_property(handle, "location")` → `__emval_get_property(handle, "origin")`
         * → expects "https://hanime.tv"
         *
         * Self-referencing window.top === window requires careful ordering:
         * 1. Allocate the window JsObject first (with an empty property map)
         * 2. Then set the "top" property to point back to its own handle
         */
        private fun initializeMockEnvironment() {
            // ── String values ──────────────────────────────────────────
            val originHandle = allocate(EmvalValue.JsString("https://hanime.tv"))
            val hrefHandle = allocate(EmvalValue.JsString("https://hanime.tv/"))
            val hostnameHandle = allocate(EmvalValue.JsString("hanime.tv"))
            val protocolHandle = allocate(EmvalValue.JsString("https:"))

            // ── Document string properties ─────────────────────────────
            val domainHandle = allocate(EmvalValue.JsString("hanime.tv"))
            val referrerHandle = allocate(EmvalValue.JsString(""))
            val cookieHandle = allocate(EmvalValue.JsString(""))
            val titleHandle = allocate(EmvalValue.JsString("hanime.tv"))

            // ── Navigator string properties ────────────────────────────
            val navigatorUserAgent = allocate(
                EmvalValue.JsString(
                    "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36",
                ),
            )
            val navigatorPlatform = allocate(EmvalValue.JsString("Linux armv8l"))
            val navigatorLanguage = allocate(EmvalValue.JsString("en-US"))
            val navigatorAppVersion = allocate(
                EmvalValue.JsString(
                    "5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36",
                ),
            )

            // ── Function stubs ─────────────────────────────────────────
            val addEventListenerHandle = allocate(EmvalValue.JsFunction("addEventListener"))
            val dispatchEventHandle = allocate(EmvalValue.JsFunction("dispatchEvent"))
            val wasmExportsHandle = allocate(EmvalValue.JsObject())

            // ── Location object ────────────────────────────────────────
            // Create with known handles — location properties already allocated above
            locationHandle = allocate(
                EmvalValue.JsObject(
                    ConcurrentHashMap(
                        mapOf(
                            "origin" to originHandle,
                            "href" to hrefHandle,
                            "hostname" to hostnameHandle,
                            "protocol" to protocolHandle,
                        ),
                    ),
                ),
            )

            // ── Console object (no properties needed) ──────────────────
            consoleHandle = allocate(EmvalValue.JsObject())

            // ── Navigator object ───────────────────────────────────────
            navigatorHandle = allocate(
                EmvalValue.JsObject(
                    ConcurrentHashMap(
                        mapOf(
                            "userAgent" to navigatorUserAgent,
                            "platform" to navigatorPlatform,
                            "language" to navigatorLanguage,
                            "appVersion" to navigatorAppVersion,
                        ),
                    ),
                ),
            )

            // ── Document object ────────────────────────────────────────
            documentHandle = allocate(
                EmvalValue.JsObject(
                    ConcurrentHashMap(
                        mapOf(
                            "domain" to domainHandle,
                            "referrer" to referrerHandle,
                            "cookie" to cookieHandle,
                            "title" to titleHandle,
                        ),
                    ),
                ),
            )

            // ── Window object (self-referencing: window.top === window) ─
            // Create the JsObject with a mutable map, allocate to get a handle,
            // then add the "top" self-reference after allocation.
            val windowProps = ConcurrentHashMap(
                mapOf(
                    "location" to locationHandle,
                    "ssignature" to 0, // initially undefined
                    "stime" to 0, // initially undefined
                    "addEventListener" to addEventListenerHandle,
                    "dispatchEvent" to dispatchEventHandle,
                    "wasmExports" to wasmExportsHandle,
                ),
            )
            windowHandle = allocate(EmvalValue.JsObject(windowProps))
            // Self-reference: window.top === window
            windowProps["top"] = windowHandle
        }
    }

    /**
     * Parse the emscripten ASM_CONSTS argument buffer, mirroring the JS `readEmAsmArgs`.
     *
     * Signature characters:
     * - 'p' = pointer (I32, 4 bytes, aligned to 4)
     * - 'i' = int (I32, 4 bytes, aligned to 4)
     * - 'j' = i64 (I64, 8 bytes, aligned to 8)
     * - 'd' = double (F64, 8 bytes, aligned to 8)
     *
     * Non-'p'/'i' chars are "wide" (8 bytes) and get 8-byte alignment.
     * 'p' and 'i' are narrow (4 bytes) with 4-byte alignment.
     */
    private fun readEmAsmArgs(sigPtr: Int, argBuf: Int, instance: Instance): LongArray {
        val result = mutableListOf<Long>()
        val mem = instance.memory()
        var buf = argBuf
        var offset = 0

        var iterations = 0
        while (iterations < MAX_SIGNATURE_LENGTH) {
            val ch = mem.readU8(sigPtr + offset).toInt().toChar()
            if (ch == '\u0000') break // null terminator
            iterations++
            offset++

            val wide = ch != 'i' && ch != 'p'
            // Align to 8 for wide types
            if (wide && buf % 8 != 0) {
                buf += 4
            }

            result.add(
                when (ch) {
                    'p' -> mem.readI32(buf) and 0xFFFFFFFFL // unsigned I32 pointer
                    'i' -> mem.readI32(buf) // signed I32
                    'j' -> mem.readI64(buf) // I64
                    'd' -> {
                        // F64 bits — read raw 8 bytes as Long for bit-exact representation
                        val low = mem.readI32(buf) and 0xFFFFFFFFL
                        val high = mem.readI32(buf + 4) and 0xFFFFFFFFL
                        (high shl 32) or low
                    }
                    else -> 0L // unknown type char — skip with 0
                },
            )
            buf += if (wide) 8 else 4
        }

        return result.toLongArray()
    }

    /** Reset captured state before a new signature generation cycle. */
    fun reset() {
        capturedSignature = null
        capturedTimestamp = null
        emvalManager.releaseTransients()
        // NOTE: registeredEventTypes is NOT cleared here.
        // Event type registrations are one-time setup performed during _main()
        // and must persist for the lifetime of the WASM instance.
        // Only captured output values need clearing before each new computation.
    }

    /** Full reset including event type registrations and emval handles — use only when re-initializing the WASM instance. */
    fun fullReset() {
        capturedSignature = null
        capturedTimestamp = null
        registeredEventTypes.clear()
        emvalManager.clear()
    }

    /**
     * Build all 25 [HostFunction] imports for module "a".
     *
     * IMPORTANT: The function signature for each binding MUST exactly match what the
     * WASM binary declares in its import section. If there's a mismatch, Chicory
     * will throw at instantiation time with a clear error message — adjust the
     * FunctionType accordingly.
     */
    fun buildHostFunctions(): List<HostFunction> {
        val module = "a"
        val functions = mutableListOf<HostFunction>()

        // ═══ a = embind type registration (i32, i32, i32) -> () ═══
        functions += HostFunction(
            module,
            "a",
            listOf(ValueType.I32, ValueType.I32, ValueType.I32),
            emptyList(),
        ) { _, _ -> null }

        // ═══ b = embind type registration (i32, i32, i32, i32, i32) -> () ═══
        functions += HostFunction(
            module,
            "b",
            listOf(ValueType.I32, ValueType.I32, ValueType.I32, ValueType.I32, ValueType.I32),
            emptyList(),
        ) { _, _ -> null }

        // ═══ c = __emval_decref (i32) -> () ═══
        functions += HostFunction(
            module,
            "c",
            listOf(ValueType.I32),
            emptyList(),
        ) { _, args ->
            val handle = args[0].toInt()
            if (handle > 0) {
                emvalManager.release(handle)
            }
            null
        }

        // ═══ d = embind type registration (i32, i32, i32) -> () ═══
        functions += HostFunction(
            module,
            "d",
            listOf(ValueType.I32, ValueType.I32, ValueType.I32),
            emptyList(),
        ) { _, _ -> null }

        // ═══ e = embind type registration (i32, i32, i32) -> () ═══
        functions += HostFunction(
            module,
            "e",
            listOf(ValueType.I32, ValueType.I32, ValueType.I32),
            emptyList(),
        ) { _, _ -> null }

        // ═══ f = embind type registration (i32, i32, i32, i64, i64) -> () ═══
        functions += HostFunction(
            module,
            "f",
            listOf(ValueType.I32, ValueType.I32, ValueType.I32, ValueType.I64, ValueType.I64),
            emptyList(),
        ) { _, _ -> null }

        // ═══ g = _emscripten_asm_const_int — CRITICAL ASM_CONSTS BRIDGE ═══
        // This is how the WASM binary communicates with the JS world.
        // Signature: (i32, i32, i32) -> (i32)
        // args[0] = code — ASM_CONSTS integer ID
        // args[1] = sigPtr — pointer to signature string (e.g. "pi" = pointer + int)
        // args[2] = argbuf — pointer to argument buffer in WASM memory
        //
        // Mirrors the JS: _emscripten_asm_const_int = (code, sigPtr, argbuf) =>
        // runEmAsmFunction(code, sigPtr, argbuf)
        // where runEmAsmFunction reads the signature, parses argbuf, then
        // dispatches to ASM_CONSTS[code](...parsedArgs).
        functions += HostFunction(
            module,
            "g",
            listOf(ValueType.I32, ValueType.I32, ValueType.I32),
            listOf(ValueType.I32),
        ) { instance, args ->
            val code = args[0].toInt()
            val sigPtr = args[1].toInt()
            val argBuf = args[2].toInt()

            val handler = asmConsts[code]
            val result = if (handler != null) {
                val parsedArgs = readEmAsmArgs(sigPtr, argBuf, instance)
                handler(parsedArgs, instance)
            } else {
                Log.w(TAG, "ASM_CONSTS dispatch: unknown ID=$code — returning 0")
                0L
            }
            longArrayOf(result)
        }

        // ═══ h = __emval_run_destructors (i32) -> () ═══
        functions += HostFunction(
            module,
            "h",
            listOf(ValueType.I32),
            emptyList(),
        ) { _, _ -> null }

        // ═══ i = __emval_invoke (i32, i32, i32, i32, i32) -> (f64) ═══
        functions += HostFunction(
            module,
            "i",
            listOf(ValueType.I32, ValueType.I32, ValueType.I32, ValueType.I32, ValueType.I32),
            listOf(ValueType.F64),
        ) { _, args ->
            val functionHandle = args[0].toInt()
            val emval = emvalManager.get(functionHandle)
            Log.d(TAG, "emval_invoke: handle=$functionHandle, emval=$emval")
            // Return 0.0 for most invocations (void functions).
            // The critical path (addEventListener) is handled by import y.
            longArrayOf(0L)
        }

        // ═══ j = __emval_incref (i32) -> () ═══
        functions += HostFunction(
            module,
            "j",
            listOf(ValueType.I32),
            emptyList(),
        ) { _, _ -> null }

        // ═══ k = __emval_create_invoker (i32, i32, i32) -> (i32) ═══
        functions += HostFunction(
            module,
            "k",
            listOf(ValueType.I32, ValueType.I32, ValueType.I32),
            listOf(ValueType.I32),
        ) { _, _ ->
            val handle = emvalManager.allocate(EmvalHandleManager.EmvalValue.JsFunction("invoker"))
            longArrayOf(handle.toLong())
        }

        // ═══ l = __emval_get_property (i32, i32) -> (i32) ═══
        functions += HostFunction(
            module,
            "l",
            listOf(ValueType.I32, ValueType.I32),
            listOf(ValueType.I32),
        ) { instance, args ->
            val objectHandle = args[0].toInt()
            val propNamePtr = args[1].toInt()
            val propName = instance.memory().readCString(propNamePtr)
            val resultHandle = when (val emval = emvalManager.get(objectHandle)) {
                is EmvalHandleManager.EmvalValue.JsObject -> {
                    emval.properties[propName] ?: 0 // undefined if not found
                }
                is EmvalHandleManager.EmvalValue.JsString -> {
                    when (propName) {
                        "length" -> {
                            val handle = emvalManager.allocate(
                                EmvalHandleManager.EmvalValue.JsNumber(emval.value.length.toDouble()),
                            )
                            emvalManager.markTransient(handle)
                            handle
                        }
                        else -> 0
                    }
                }
                else -> {
                    Log.d(TAG, "emval_get_property: handle=$objectHandle ($emval) has no property '$propName' — returning undefined")
                    0 // undefined
                }
            }
            longArrayOf(resultHandle.toLong())
        }

        // ═══ m = __emval_new_cstring (i32) -> (i32) ═══
        functions += HostFunction(
            module,
            "m",
            listOf(ValueType.I32),
            listOf(ValueType.I32),
        ) { instance, args ->
            val strPtr = args[0].toInt()
            val str = instance.memory().readCString(strPtr)
            val handle = emvalManager.allocate(EmvalHandleManager.EmvalValue.JsString(str))
            longArrayOf(handle.toLong())
        }

        // ═══ n = embind destructor (i32) -> () ═══
        functions += HostFunction(
            module,
            "n",
            listOf(ValueType.I32),
            emptyList(),
        ) { _, _ -> null }

        // ═══ o = embind type registration (i32, i32) -> () ═══
        functions += HostFunction(
            module,
            "o",
            listOf(ValueType.I32, ValueType.I32),
            emptyList(),
        ) { _, _ -> null }

        // ═══ p = __emval_get_global (i32) -> (i32) ═══
        functions += HostFunction(
            module,
            "p",
            listOf(ValueType.I32),
            listOf(ValueType.I32),
        ) { instance, args ->
            val globalNamePtr = args[0].toInt()
            val globalName = instance.memory().readCString(globalNamePtr)
            val handle = when (globalName) {
                "window", "self", "globalThis", "top" -> emvalManager.windowHandle
                "document" -> emvalManager.documentHandle
                "navigator" -> emvalManager.navigatorHandle
                "location" -> emvalManager.locationHandle
                "console" -> emvalManager.consoleHandle
                else -> {
                    Log.d(TAG, "emval_get_global: unknown global '$globalName' — returning undefined")
                    0 // undefined handle
                }
            }
            longArrayOf(handle.toLong())
        }

        // ═══ q = embind type registration (i32, i32, i32, i32) -> () ═══
        functions += HostFunction(
            module,
            "q",
            listOf(ValueType.I32, ValueType.I32, ValueType.I32, ValueType.I32),
            emptyList(),
        ) { _, _ -> null }

        // ═══ r = embind type registration (i32, i32) -> () ═══
        functions += HostFunction(
            module,
            "r",
            listOf(ValueType.I32, ValueType.I32),
            emptyList(),
        ) { _, _ -> null }

        // ═══ ENVIRONMENT / TIMEZONE ═══

        // s = __tzset_js (i32, i32, i32, i32) -> () — writes timezone data to WASM memory
        // timezonePtr: pointer to write current timezone UTC offset (minutes, i32)
        // dstPtr: pointer to write DST offset (minutes, i32)
        // tznamePtr: pointer to write timezone name string (null-terminated)
        // tznameLen: max length of timezone name string buffer
        functions += HostFunction(
            module,
            "s",
            listOf(ValueType.I32, ValueType.I32, ValueType.I32, ValueType.I32),
            emptyList(),
        ) { instance, args ->
            val timezonePtr = args[0].toInt()
            val dstPtr = args[1].toInt()
            val tznamePtr = args[2].toInt()
            val tznameLen = args[3].toInt()

            // Java ZONE_OFFSET sign convention is opposite to JS getTimezoneOffset():
            // JS: UTC-5 → getTimezoneOffset() returns 300 (positive = west of UTC)
            // Java: UTC-5 → ZONE_OFFSET returns -18000000ms (negative = west of UTC)
            // So we negate to match the JS convention the WASM binary expects.
            val rawOffsetMs = java.util.Calendar.getInstance().get(java.util.Calendar.ZONE_OFFSET)
            val jsOffsetMinutes = -(rawOffsetMs / 60000)

            instance.memory().writeI32(timezonePtr, jsOffsetMinutes)
            instance.memory().writeI32(dstPtr, jsOffsetMinutes)
            instance.memory().writeCString(tznamePtr, java.util.TimeZone.getDefault().id ?: "UTC")

            null
        }

        // t = _environ_get (i32, i32) -> (i32) — returns 0 (success)
        functions += HostFunction(
            module,
            "t",
            listOf(ValueType.I32, ValueType.I32),
            listOf(ValueType.I32),
        ) { _, _ -> longArrayOf(0L) }

        // u = _environ_sizes_get (i32, i32) -> (i32) — writes 0 to both pointers
        functions += HostFunction(
            module,
            "u",
            listOf(ValueType.I32, ValueType.I32),
            listOf(ValueType.I32),
        ) { instance, args ->
            instance.memory().writeI32(args[0].toInt(), 0) // 0 env vars
            instance.memory().writeI32(args[1].toInt(), 0) // 0 buffer size
            longArrayOf(0L)
        }

        // ═══ ERROR STUBS — throw on invocation ═══

        // v = __abort_js () -> ()
        functions += HostFunction(
            module,
            "v",
            emptyList(),
            emptyList(),
        ) { _, _ ->
            Log.e(TAG, "Error stub 'v' (__abort_js) called — WASM execution error")
            throw RuntimeException(
                "Emscripten error stub 'v' (__abort_js) called — WASM execution error",
            )
        }

        // ═══ HEAP MANAGEMENT ═══

        // w = _emscripten_resize_heap (i32) -> (i32) — grow memory if needed
        functions += HostFunction(
            module,
            "w",
            listOf(ValueType.I32),
            listOf(ValueType.I32),
        ) { instance, args ->
            val requestedSize = args[0].toInt()
            val currentPages = instance.memory().pages()
            val neededPages = (requestedSize + 65535) / 65536
            if (neededPages <= currentPages) {
                longArrayOf(1L) // already enough memory
            } else {
                val delta = neededPages - currentPages
                val result = instance.memory().grow(delta)
                longArrayOf(if (result >= 0) 1L else 0L) // 1 = success, 0 = failure
            }
        }

        // ═══ x = ___cxa_throw (i32, i32, i32) -> () — error stub ═══
        functions += HostFunction(
            module,
            "x",
            listOf(ValueType.I32, ValueType.I32, ValueType.I32),
            emptyList(),
        ) { _, args ->
            Log.e(TAG, "Error stub 'x' (___cxa_throw) called — WASM execution error. Args: ${args.toList()}")
            throw RuntimeException(
                "Emscripten error stub 'x' (___cxa_throw) called — WASM execution error. Args: ${args.toList()}",
            )
        }

        // ═══ EVENT LISTENER REGISTRATION ═══

        // y = window_on (i32) -> () — reads event type string, records it
        functions += HostFunction(
            module,
            "y",
            listOf(ValueType.I32),
            emptyList(),
        ) { instance, args ->
            val eventTypePtr = args[0].toInt()
            val eventType = instance.memory().readCString(eventTypePtr)
            registeredEventTypes.add(eventType)
            null
        }

        return functions
    }

    companion object {
        private const val TAG = "ChicoryGlue"
        const val ASM_CONST_TIMESTAMP = 17392
        const val ASM_CONST_SIGNATURE = 17442

        /** Maximum signature string length to prevent infinite loops on corrupted memory. */
        private const val MAX_SIGNATURE_LENGTH = 32
    }
}
