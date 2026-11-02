// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "Pera",
    platforms: [.macOS(.v13)], // MenuBarExtra y SMAppService
    targets: [
        .executableTarget(name: "Pera", path: "Sources/Pera"),
        .testTarget(name: "PeraTests", dependencies: ["Pera"], path: "Tests/PeraTests"),
    ]
)
