package eu.kanade.tachiyomi.animeextension.en.hanime

import android.util.Log
import com.dylibso.chicory.runtime.ByteBufferMemory
import com.dylibso.chicory.runtime.ImportValues
import com.dylibso.chicory.runtime.Instance
import com.dylibso.chicory.wasm.ChicoryException
import com.dylibso.chicory.wasm.Parser
import com.dylibso.chicory.wasm.types.MemoryLimits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Signature provider that uses the Chicory WASM runtime to execute
 * the hanime.tv emscripten-compiled WASM binary for signature generation.
 *
 * Replaces [WasmSignatureProvider] with the production-grade Chicory runtime,
 * eliminating the custom ~5600-line WASM interpreter.
 */
class ChicorySignatureProvider(
    private val wasmBinary: ByteArray,
) : SignatureProvider {

    companion object {
        private const val TAG = "ChicorySigProvider"
    }

    override val name: String = "ChicoryInterpreter"

    private var instance: Instance? = null
    private var glue: ChicoryGlue? = null

    @Volatile
    private var isInitialized = false

    @Volatile
    private var isClosed = false

    /** Guards concurrent initialization attempts in [getSignature]. */
    private val initMutex = Mutex()

    /** Initialize the WASM runtime. Called internally by [getSignature]. */
    private fun initialize() {
        if (isInitialized) return

        Log.d(TAG, "initialize() — starting WASM runtime initialization")
        try {
            // 1. Parse the WASM binary
            Log.d(TAG, "initialize() — parsing WASM binary, size=${wasmBinary.size} bytes")
            val module = Parser.parse(wasmBinary)
            Log.d(TAG, "initialize() — WASM module parsed successfully")

            // 2. Create the glue layer with host function bindings
            glue = ChicoryGlue()
            val hostFunctions = glue!!.buildHostFunctions()
            Log.d(TAG, "initialize() — host functions built: count=${hostFunctions.size}")

            // 3. Build import values
            val imports = ImportValues.builder()
                .addFunction(*hostFunctions.toTypedArray())
                .build()

            // 4. Instantiate the WASM module
            // - ByteBufferMemory (Android-safe, pure Java NIO)
            // - Initialize globals, data segments, element segments
            // - Do NOT auto-call _start (we call exports manually)
            Log.d(TAG, "initialize() — building instance: memoryLimits=min=258,max=65536, init=true, start=false")
            instance = Instance.builder(module)
                .withImportValues(imports)
                .withInitialize(true)
                .withStart(false)
                .withMemoryFactory { _ -> ByteBufferMemory(MemoryLimits(258, 65536)) }
                .build()
            Log.d(TAG, "initialize() — WASM instance created successfully")

            // 5. Memory growth is handled by the WASM binary's _emscripten_resize_heap
            // import, which delegates to instance.memory().grow() as needed.

            // 6. Call initRuntime() — export "A"
            try {
                Log.d(TAG, "initialize() — calling initRuntime (export \"A\")")
                instance!!.export("A").apply()
                Log.d(TAG, "initialize() — initRuntime (export \"A\") completed successfully")
            } catch (e: ChicoryException) {
                Log.e(TAG, "initialize() — WASM trap during initRuntime (export \"A\"): ${e.message}", e)
                throw SignatureException("WASM trap during initRuntime (export A): ${e.message}", e)
            } catch (e: Exception) {
                Log.w(TAG, "initialize() — initRuntime (export \"A\") not available or failed (non-fatal): ${e.javaClass.simpleName}: ${e.message}")
            }

            // 7. Call _main() — export "C"
            // This registers the "e" event listener via import "y" (window_on)
            try {
                Log.d(TAG, "initialize() — calling _main (export \"C\") with argc=0, argv=0")
                instance!!.export("C").apply(0L, 0L) // argc=0, argv=0
                Log.d(TAG, "initialize() — _main (export \"C\") completed successfully")
            } catch (e: ChicoryException) {
                Log.e(TAG, "initialize() — WASM trap during _main (export \"C\"): ${e.message}", e)
                throw SignatureException("WASM trap during _main (export C): ${e.message}", e)
            } catch (e: Exception) {
                Log.w(TAG, "initialize() — _main (export \"C\") not available or failed (non-fatal): ${e.javaClass.simpleName}: ${e.message}")
            }

            isInitialized = true
            Log.d(TAG, "initialize() — initialization complete, isInitialized=true")
        } catch (e: Exception) {
            Log.e(TAG, "initialize() — FAILED: ${e.javaClass.simpleName}: ${e.message}", e)
            close()
            throw SignatureException("Failed to initialize Chicory WASM runtime: ${e.message}", e)
        }
    }

    /**
     * Attempt to re-initialize the WASM runtime after a failure.
     * Tears down the current instance and rebuilds from scratch.
     */
    private suspend fun reinitialize() {
        Log.d(TAG, "reinitialize() — called, will tear down and rebuild WASM instance")
        withContext(Dispatchers.Default) {
            initMutex.withLock {
                // Tear down existing instance
                Log.d(TAG, "reinitialize() — tearing down existing instance: isInitialized=$isInitialized, instance=${instance != null}, glue=${glue != null}")
                isInitialized = false
                instance = null
                glue?.fullReset()
                Log.d(TAG, "reinitialize() — glue fullReset done, isClosed=$isClosed")
                glue = null
                if (!isClosed) {
                    Log.d(TAG, "reinitialize() — attempting fresh initialization")
                    initialize()
                } else {
                    Log.w(TAG, "reinitialize() — provider is closed, skipping re-initialization")
                }
            }
        }
    }

    override suspend fun getSignature(): Signature {
        Log.d(TAG, "getSignature() — entry, isClosed=$isClosed, isInitialized=$isInitialized")
        if (isClosed) {
            Log.e(TAG, "getSignature() — provider is closed, cannot generate signature")
            throw SignatureException("Cannot generate signature — provider has been closed")
        }
        initMutex.withLock {
            if (!isInitialized) {
                Log.d(TAG, "getSignature() — initialization needed, calling initialize()")
                withContext(Dispatchers.Default) { initialize() }
            } else {
                Log.d(TAG, "getSignature() — already initialized, proceeding")
            }
        }

        return withContext(Dispatchers.Default) {
            val currentInstance = instance ?: throw SignatureException("WASM instance unavailable — provider may have been closed")
            val currentGlue = glue ?: throw SignatureException("WASM glue unavailable — provider may have been closed")
            Log.d(TAG, "getSignature() — generating signature")

            try {
                val sig = generateSignature(currentInstance, currentGlue)
                Log.d(TAG, "getSignature() — signature generated (length=${sig.signature.length}), time=${sig.time}")
                sig
            } catch (e: SignatureException) {
                Log.w(TAG, "getSignature() — SignatureException: ${e.message}, will attempt re-initialization")
                // If the WASM instance may be in a bad state, try re-initializing once
                if (isClosed) throw e
                try {
                    Log.d(TAG, "getSignature() — attempting re-initialization after failure")
                    reinitialize()
                    val newInstance = instance
                        ?: throw SignatureException("WASM instance unavailable after re-initialization")
                    val newGlue = glue
                        ?: throw SignatureException("WASM glue unavailable after re-initialization")
                    val retrySig = generateSignature(newInstance, newGlue)
                    Log.d(TAG, "getSignature() — retry signature generated (length=${retrySig.signature.length}), time=${retrySig.time}")
                    retrySig
                } catch (retryEx: SignatureException) {
                    Log.e(TAG, "getSignature() — retry FAILED after re-initialization: ${retryEx.message}", retryEx)
                    throw SignatureException(
                        "Signature generation failed after re-initialization: ${retryEx.message}",
                        retryEx,
                    )
                }
            }
        }
    }

    /**
     * Generate a signature using the given WASM instance and glue layer.
     * This is the core computation extracted for retry support.
     */
    private fun generateSignature(currentInstance: Instance, currentGlue: ChicoryGlue): Signature {
        Log.d(TAG, "generateSignature() — entry")
        try {
            currentGlue.reset()
            Log.d(TAG, "generateSignature() — glue reset complete")
            val memory = currentInstance.memory()

            // Allocate strings in WASM memory using the binary's own malloc (export "E")
            var eventTypePtr: Int
            var eventJsonPtr: Int
            var useMalloc = false

            try {
                val malloc = currentInstance.export("E")
                eventTypePtr = malloc.apply(2L)[0].toInt()
                Log.d(TAG, "generateSignature() — malloc for event type returned ptr=$eventTypePtr")
                if (eventTypePtr == 0) {
                    throw SignatureException("WASM malloc returned null pointer for event type string")
                }
                eventJsonPtr = malloc.apply(3L)[0].toInt()
                Log.d(TAG, "generateSignature() — malloc for event JSON returned ptr=$eventJsonPtr")
                if (eventJsonPtr == 0) {
                    throw SignatureException("WASM malloc returned null pointer for event JSON string")
                }
                useMalloc = true
            } catch (e: SignatureException) {
                throw e
            } catch (e: Exception) {
                throw SignatureException("WASM module does not export required malloc function (export E): ${e.message}", e)
            }

            try {
                // Write the event type and JSON into WASM memory
                Log.d(TAG, "generateSignature() — writing to WASM memory: eventType=\"e\" at ptr=$eventTypePtr, eventJson=\"{}\" at ptr=$eventJsonPtr")
                memory.writeCString(eventTypePtr, "e")
                memory.writeCString(eventJsonPtr, "{}")

                // Call _on_window_event(eventTypePtr, eventJsonPtr) — export "B"
                Log.d(TAG, "generateSignature() — calling _on_window_event (export \"B\") with eventTypePtr=$eventTypePtr, eventJsonPtr=$eventJsonPtr")
                currentInstance.export("B").apply(
                    eventTypePtr.toLong(),
                    eventJsonPtr.toLong(),
                )
                Log.d(TAG, "generateSignature() — _on_window_event (export \"B\") returned successfully")
            } finally {
                // Free allocated memory if we used malloc
                if (useMalloc) {
                    try {
                        val free = currentInstance.export("F")
                        free.apply(eventTypePtr.toLong())
                        free.apply(eventJsonPtr.toLong())
                        Log.d(TAG, "generateSignature() — freed malloc memory: eventTypePtr=$eventTypePtr, eventJsonPtr=$eventJsonPtr")
                    } catch (e: Exception) {
                        Log.w(TAG, "generateSignature() — free failed (non-fatal): ${e.javaClass.simpleName}: ${e.message}")
                    }
                }
            }

            // Read captured signature and timestamp from the glue layer
            val signature = currentGlue.capturedSignature
                ?: throw SignatureException("WASM execution did not produce a signature")
            val timestamp = currentGlue.capturedTimestamp
                ?: throw SignatureException("WASM execution did not produce a timestamp")
            Log.d(TAG, "generateSignature() — captured signature (length=${signature.length}), timestamp=$timestamp")

            // Validate the signature before returning — a corrupted or stale
            // signature would cause 401 errors on the manifest endpoint.
            val result = Signature(signature, timestamp.toString()).also { it.validate() }
            Log.d(TAG, "generateSignature() — signature validated OK, returning Signature(length=${result.signature.length}, time=${result.time})")
            return result
        } catch (e: SignatureException) {
            throw e
        } catch (e: Exception) {
            throw SignatureException("WASM signature generation failed: ${e.message}", e)
        }
    }

    override fun close() {
        Log.d(TAG, "close() — called, isClosed=$isClosed, isInitialized=$isInitialized")
        // Mark closed first to prevent new getSignature() calls from proceeding.
        // This must happen-before the field nulling below.
        isClosed = true
        isInitialized = false

        // Null out heavy resources without acquiring the mutex.
        // If a getSignature() call is in progress, it holds its own references
        // on the stack and will complete (or fail on next attempt).
        instance = null
        Log.d(TAG, "close() — calling glue.fullReset()")
        glue?.fullReset()
        glue = null
        Log.d(TAG, "close() — complete")
    }
}
