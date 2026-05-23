{
  description = "A basic flake with a shell";
  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixpkgs-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };
  outputs = {
    self,
    nixpkgs,
    flake-utils,
  }:
    flake-utils.lib.eachDefaultSystem (system: let
      pkgs = nixpkgs.legacyPackages.${system};
      androidComposition = pkgs.androidenv.composeAndroidPackages {
        cmdLineToolsVersion = "8.0"; # Or latest available
        platformVersions = ["35" "36"]; # Match your project's compileSdk
        buildToolsVersions = ["35.0.0" "36.0.0"];
        includeEmulator = false;
      };
      fhs = pkgs.buildFHSEnv {
        name = "android-sdk-env";
        targetPkgs = pkgs:
          with pkgs; [
            # Core build environments
            androidComposition.androidsdk
            glibc
            jdk21 # Match the JDK version required by your Gradle wrapper
            git
            gnumake

            # Common libraries Android build tools dynamically link against
            zlib
            ncurses
            libxml2
          ];

        profile = ''
          export ANDROID_HOME="${androidComposition.androidsdk}/libexec/android-sdk"
          export ANDROID_SDK_ROOT="$ANDROID_HOME"
          export JAVA_HOME="${pkgs.jdk21}/lib/openjdk"
        '';

        runScript = "bash";
      };
    in {
      devShell = fhs.env;
    });
}
#       pkgs.mkShell {
#         packages = with pkgs; [
#           androidSdk
#           jdk21
#           gradle_9
#           kotlin
#           python3
#         ];
#         shellHook = ''
#           export ANDROID_SDK_ROOT="${androidSdk}";
#           export GRADLE_OPTS="-Dorg.gradle.project.android.aapt2FromMavenOverride=${androidSdk}/libexec/android-sdk/build-tools/28.0.3/aapt2";
#         '';
#       };
#   });
#}

