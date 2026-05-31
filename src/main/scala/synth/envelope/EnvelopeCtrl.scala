package synth.envelope

import spinal.core._
import spinal.lib._
import spinal.lib.fsm._
import synth.common.EnvelopeConfig

class EnvelopeCtrl extends Component {
  val io = new Bundle {
    // Inputs
    val syncIn      = in Bool()
    val midiClock   = in Bool()
    val config      = in(EnvelopeConfig())
    val segmentDone = in Bool()

    // Outputs to Accumulator
    val resetAccum   = out Bool()
    val runAccum     = out Bool()
    val accumDir     = out Bool()
    val phaseInc     = out UInt(22 bits)

    // Outputs to Shaper
    val curveSelect  = out UInt(2 bits)
    val activeStage  = out UInt(3 bits)       // IDLE=0, ATTACK=1, DECAY=2, SUSTAIN=3, RELEASE=4
  }

  // Gate input is mapped to bit 1 of the ctrl register (qualified to remain False during active reset)
  val gateOn = io.config.ctrl(1) && !ClockDomain.current.isResetActive

  // -------------------------------------------------------------------------
  // Logarithmic Increment ROM Mapping (256 words x 22 bits)
  // -------------------------------------------------------------------------
  val clockFreq = 24000000.0   // 24 MHz
  val tMin      = 0.0005       // 0.5 ms
  val tMax      = 30.0         // 30.0 s

  // Scala-time generator loop for ROM initialization
  val lutContent = for (p <- 0 until 256) yield {
    val t = tMin * scala.math.pow(tMax / tMin, p / 255.0)
    val inc = scala.math.round(scala.math.pow(2, 32) / (t * clockFreq))
    U(inc, 22 bits)
  }
  val rom = Mem(UInt(22 bits), 256) init(lutContent)

  // Select ROM address based on the current active FSM stage
  val romAddr = UInt(8 bits)
  romAddr := 0
  switch(io.activeStage) {
    is(1) { romAddr := io.config.attack }
    is(2) { romAddr := io.config.decay }
    is(4) { romAddr := io.config.release }
  }
  io.phaseInc := rom.readAsync(romAddr)

  // -------------------------------------------------------------------------
  // Control Parameters & Edge Logic
  // -------------------------------------------------------------------------
  // Curve Select: ctrl[6:5]
  io.curveSelect := io.config.ctrl(6 downto 5).asUInt

  // Hard Sync: Rising edge detection on syncIn qualified with syncCtrl[0] (Hard Sync Enable) and reset inactive
  val syncInD1 = RegNext(io.syncIn) init(false)
  val hardSyncPulse = io.syncIn && !syncInD1 && io.config.syncCtrl(0) && !ClockDomain.current.isResetActive

  // Direction control: Reverse Mode active if ctrl[4] = True
  io.accumDir := io.config.ctrl(4)

  // -------------------------------------------------------------------------
  // ADSR State Machine (built-in spinal.lib.fsm)
  // -------------------------------------------------------------------------
  val fsm = new StateMachine {
    val IDLE    = new State with EntryPoint
    val ATTACK  = new State
    val DECAY   = new State
    val SUSTAIN = new State
    val RELEASE = new State

    // Default outputs driven from FSM
    io.resetAccum  := False
    io.runAccum    := False
    io.activeStage := 0

    IDLE.whenIsActive {
      io.activeStage := 0
      when(gateOn) {
        io.resetAccum := True
        io.runAccum   := True
        goto(ATTACK)
      }
    }

    ATTACK.whenIsActive {
      io.activeStage := 1
      io.runAccum    := True
      when(hardSyncPulse) {
        io.resetAccum := True
        goto(ATTACK)
      } elsewhen(!gateOn) {
        io.resetAccum := True
        goto(RELEASE)
      } elsewhen(io.segmentDone) {
        io.resetAccum := True
        goto(DECAY)
      }
    }

    DECAY.whenIsActive {
      io.activeStage := 2
      io.runAccum    := True
      when(hardSyncPulse) {
        io.resetAccum := True
        goto(ATTACK)
      } elsewhen(!gateOn) {
        io.resetAccum := True
        goto(RELEASE)
      } elsewhen(io.segmentDone) {
        // If Loop Mode (ctrl[2]) is active, loop back to Attack
        when(io.config.ctrl(2)) {
          io.resetAccum := True
          goto(ATTACK)
        } otherwise {
          goto(SUSTAIN)
        }
      }
    }

    SUSTAIN.whenIsActive {
      io.activeStage := 3
      when(hardSyncPulse) {
        io.resetAccum := True
        goto(ATTACK)
      } elsewhen(!gateOn) {
        io.resetAccum := True
        goto(RELEASE)
      }
    }

    RELEASE.whenIsActive {
      io.activeStage := 4
      io.runAccum    := True
      when(hardSyncPulse) {
        io.resetAccum := True
        goto(ATTACK)
      } elsewhen(gateOn) {
        io.resetAccum := True
        goto(ATTACK)
      } elsewhen(io.segmentDone) {
        goto(IDLE)
      }
    }
  }
}
