import Darwin
import Metal

guard let device = MTLCreateSystemDefaultDevice() else {
    print("No system Metal device")
    exit(1)
}
print("device=\(device.name) unifiedMemory=\(device.hasUnifiedMemory) registryID=\(device.registryID)")

let options = MTLCompileOptions()
options.mathMode = .safe
for kind in ["float", "double"] {
    let shader = """
    #include <metal_stdlib>
    using namespace metal;
    kernel void capability(device \(kind)* data [[buffer(0)]], uint i [[thread_position_in_grid]]) {
        data[i] += \(kind)(1);
    }
    """
    do {
        _ = try device.makeLibrary(source: shader, options: options)
        print("\(kind): compiled")
    } catch {
        print("\(kind): \(error.localizedDescription)")
        if kind == "float" {
            exit(1)
        }
    }
}
