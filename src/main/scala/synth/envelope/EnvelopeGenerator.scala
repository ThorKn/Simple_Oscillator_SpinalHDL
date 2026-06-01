package synth.envelope

import spinal.core._
import spinal.lib._
import synth.common.EnvelopeConfig

class EnvelopeGenerator extends Component {
  val io = new Bundle {
    // Clock Heartbeat & Sync inputs
    val phaseTick = in Bool()                 // Heartbeat tick synced with 480 kHz audio rate
    val syncIn    = in Bool()                 // External trigger for Hard or Soft Sync
    val midiClock = in Bool()                 // External MIDI clock tick (24 PPQN pulse)
    val config    = in(EnvelopeConfig())      // Packaged register configurations

    // System Outputs
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
  ctrl.io.segmentDone         := accumulator.io.segmentDone

  // Connecting Accumulator and Ctrl to Shaper
  shaper.io.phaseTick    := io.phaseTick
  shaper.io.baseIndex    := accumulator.io.baseIndex
  shaper.io.fraction     := accumulator.io.fraction
  shaper.io.curveSelect  := ctrl.io.curveSelect
  shaper.io.sustainLevel := io.config.sustain
  shaper.io.activeStage  := ctrl.io.activeStage
  shaper.io.accumDir     := ctrl.io.accumDir

  // Connecting Top-Level inputs to Ctrl
  ctrl.io.syncIn         := io.syncIn
  ctrl.io.midiClock      := io.midiClock
  ctrl.io.config         := io.config

  // Top-Level Outputs connected to Shaper
  io.envelopeOut       <> shaper.io.envelopeOut
  io.envelopeOutSigned <> shaper.io.envelopeOutSigned
}
