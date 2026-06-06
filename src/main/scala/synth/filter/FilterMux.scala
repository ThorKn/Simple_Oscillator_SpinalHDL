package synth.filter

import spinal.core._
import spinal.lib._

class FilterMux extends Component {
  val io = new Bundle {
    val mode      = in UInt(2 bits)
    val lp        = in SInt(24 bits)
    val bp        = in SInt(24 bits)
    val hp        = in SInt(24 bits)
    val sampleOut = out SInt(16 bits)
  }

  // Select response mode: 00 = LP, 01 = BP, 10 = HP, default/11 = LP (or quiet/reserved)
  val selected = SInt(24 bits)
  switch(io.mode) {
    is(0)   { selected := io.lp }
    is(1)   { selected := io.bp }
    is(2)   { selected := io.hp }
    default { selected := io.lp }
  }

  // Downsize 24-bit internal signal back to 16-bit output
  io.sampleOut := selected.resize(16 bits)
}
