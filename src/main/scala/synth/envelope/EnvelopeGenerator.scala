package synth.envelope

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import synth.common.EnvelopeConfig

class EnvelopeGenerator extends Component {
  val io = new Bundle {
    val phaseTick = in Bool()                 // 480 kHz audio rate tick
    val syncIn    = in Bool()                 // Trigger for Hard Sync
    val config    = in(EnvelopeConfig())      // Packaged register configurations

    val envelopeOut       = master(Flow(UInt(10 bits))) // Unipolar output (0 to 1023)
    val envelopeOutSigned = master(Flow(SInt(10 bits))) // Bipolar output (-512 to +511)
  }

  // Instantiate submodules
  val ctrl        = new EnvelopeCtrl()
  val accumulator = new EnvelopeAccumulator()
  val shaper      = new EnvelopeShaper()

  // Connecting Ctrl to Accumulator
  accumulator.io.resetAccum   := ctrl.io.resetAccum
  accumulator.io.runAccum     := ctrl.io.runAccum
  accumulator.io.accumDir     := ctrl.io.accumDir
  accumulator.io.phaseInc     := ctrl.io.phaseInc
  accumulator.io.sustainLevel := io.config.sustain
  accumulator.io.activeStage  := ctrl.io.activeStage
  ctrl.io.activeStage.simPublic()
  ctrl.io.segmentDone         := accumulator.io.segmentDone

  // Connecting Accumulator and Ctrl to Shaper
  shaper.io.phaseTick    := io.phaseTick
  shaper.io.baseIndex    := accumulator.io.baseIndex
  shaper.io.fraction     := accumulator.io.fraction
  shaper.io.curveSelect  := ctrl.io.curveSelect
  shaper.io.activeStage  := ctrl.io.activeStage
  shaper.io.accumDir     := ctrl.io.accumDir
  shaper.io.disable      := io.config.ctrl(0)

  // Connecting Top-Level inputs to Ctrl
  ctrl.io.syncIn         := io.syncIn
  ctrl.io.config         := io.config

  // Top-Level Outputs connected to Shaper
  io.envelopeOut       <> shaper.io.envelopeOut
  io.envelopeOutSigned <> shaper.io.envelopeOutSigned
}
