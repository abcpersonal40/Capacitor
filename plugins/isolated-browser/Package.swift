// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "NativeKitIsolatedBrowser",
    platforms: [.iOS(.v15)],
    products: [
        .library(name: "NativeKitIsolatedBrowser", targets: ["NativeKitIsolatedBrowserPlugin"])
    ],
    dependencies: [
        .package(url: "https://github.com/ionic-team/capacitor-swift-pm.git", exact: "8.5.0")
    ],
    targets: [
        .target(
            name: "NativeKitIsolatedBrowserPlugin",
            dependencies: [
                .product(name: "Capacitor", package: "capacitor-swift-pm"),
                .product(name: "Cordova", package: "capacitor-swift-pm")
            ],
            path: "ios/Sources/NativeKitIsolatedBrowserPlugin")
    ]
)
