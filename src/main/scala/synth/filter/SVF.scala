package synth.filter

import spinal.core._
import spinal.lib._
import synth.common.FilterConfig

class SVF extends Component {
  val io = new Bundle {
    val phaseTick = in Bool()
    val config    = in(FilterConfig())

    val sampleIn  = slave(Flow(SInt(16 bits)))
    val sampleOut = master(Flow(SInt(16 bits)))
  }

  // Instantiate submodules
  val mapper = new ParameterMapper()
  val core   = new FilterCore()
  val mux    = new FilterMux()

  // Connect Parameter Mapper
  mapper.io.cutoff    := io.config.cutoff
  mapper.io.resonance := io.config.resonance

  // Connect Filter Core
  core.io.phaseTick      := io.phaseTick
  core.io.clear          := !io.config.enable
  core.io.sampleIn       := io.sampleIn.payload
  core.io.cutoffCoeff    := mapper.io.cutoffCoeff
  core.io.resonanceCoeff := mapper.io.resonanceCoeff

  // Connect Filter Mux
  mux.io.mode := io.config.mode
  mux.io.lp   := core.io.lp
  mux.io.bp   := core.io.bp
  mux.io.hp   := core.io.hp

  // Latch the FSM output when calculation is complete (done)
  val outReg = Reg(SInt(16 bits)) init(0)
  when(core.io.done) {
    outReg := mux.io.sampleOut
  }

  // Drive output flow. Gated to 0 when disabled.
  io.sampleOut.payload := io.config.enable ? outReg | SInt(16 bits).getZero
  // The output valid pulse is synchronized back to the next phaseTick boundary
  io.sampleOut.valid   := io.phaseTick && !ClockDomain.current.isResetActive
}


