{
  pkgs ?
    import <nixpkgs> {
      config = {
        allowUnfree = true;
        android_sdk.accept_license = true;
      };
    },
}: let
  androidComposition = pkgs.androidenv.composeAndroidPackages {
    buildToolsVersions = ["34.0.0"];
    platformVersions = ["32"];
    abiVersions = ["armeabi-v7a"];
  };
  pinnedJdk = pkgs.jdk17;
in
  pkgs.mkShell {
    buildInputs = with pkgs; [
      gradle_8
      pinnedJdk
      androidComposition.androidsdk
    ];

    JAVA_HOME = pinnedJdk.home;
    ANDROID_HOME = "${androidComposition.androidsdk}/libexec/android-sdk";
    ANDROID_SDK_ROOT = "${androidComposition.androidsdk}/libexec/android-sdk";

    GRADLE_OPTS = pkgs.lib.concatStringsSep " " [
      "-Dorg.gradle.java.installations.auto-download=false"
      "-Dorg.gradle.project.android.aapt2FromMavenOverride=${pkgs.aapt}/bin/aapt2"
    ];
  }
