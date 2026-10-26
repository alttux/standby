// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "StandByCompanion",
    platforms: [.macOS(.v13)],
    targets: [
        .executableTarget(
            name: "StandByCompanion",
            path: "Sources/StandByCompanion",
            linkerSettings: [
                .linkedFramework("AppKit"),
                .linkedFramework("CoreAudio"),
                .linkedFramework("AudioToolbox"),
                .linkedFramework("AVFoundation"),
                .linkedFramework("Accelerate"),
                .linkedFramework("IOKit"),
                .linkedFramework("Network"),
                .linkedFramework("ServiceManagement"),
            ]
        ),
    ]
)
