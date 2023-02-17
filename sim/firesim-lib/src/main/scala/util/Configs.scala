//See LICENSE for license details.
package firesim.util


import freechips.rocketchip.config.Config

class WithILADepth(depth: Int) extends Config((site, here, up) => {
    case midas.ILADepthKey => depth
})

class  ILADepth1024 extends WithILADepth(1024)
class  ILADepth2048 extends WithILADepth(2048)
class  ILADepth4096 extends WithILADepth(4096)
class  ILADepth8192 extends WithILADepth(8192)
class ILADepth16384 extends WithILADepth(16384)
