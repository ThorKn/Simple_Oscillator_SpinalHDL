package synth.envelope

import spinal.core._
import spinal.lib._
import spinal.lib.fsm._
import synth.common.{EnvelopeConfig, EnvelopeStage}

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

  // Gate input is mapped to bit 1 of the ctrl register
  val gateOn = io.config.ctrl(1)

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
    is(EnvelopeStage.ATTACK)  { romAddr := io.config.attack }
    is(EnvelopeStage.DECAY)   { romAddr := io.config.decay }
    is(EnvelopeStage.RELEASE) { romAddr := io.config.release }
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

  // Direction control: Dynamically count downwards during DECAY and RELEASE phases.
  // Note: ctrl(4) (Reverse Mode) and ctrl(3) (Ping-Pong Mode) are reserved as future placeholders.
  io.accumDir := io.activeStage === EnvelopeStage.DECAY || io.activeStage === EnvelopeStage.RELEASE

  // -------------------------------------------------------------------------
  // ADSR State Machine (built-in spinal.lib.fsm)
  // -------------------------------------------------------------------------
  val fsmResetAccum  = Bool()
  val fsmRunAccum    = Bool()
  val fsmActiveStage = UInt(3 bits)

  val fsm = new StateMachine {
    val IDLE    = new State with EntryPoint
    val ATTACK  = new State
    val DECAY   = new State
    val SUSTAIN = new State
    val RELEASE = new State

    // Default outputs driven from FSM
    fsmResetAccum  := False
    fsmRunAccum    := False
    fsmActiveStage := EnvelopeStage.IDLE

    IDLE.whenIsActive {
      fsmActiveStage := EnvelopeStage.IDLE
      when(gateOn) {
        fsmResetAccum := True
        fsmRunAccum   := True
        goto(ATTACK)
      }
    }

    ATTACK.whenIsActive {
      fsmActiveStage := EnvelopeStage.ATTACK
      fsmRunAccum    := True
      when(hardSyncPulse) {
        fsmResetAccum := True
        goto(ATTACK)
      } elsewhen(!gateOn) {
        goto(RELEASE)
      } elsewhen(io.segmentDone) {
        fsmResetAccum := True
        goto(DECAY)
      }
    }

    DECAY.whenIsActive {
      fsmActiveStage := EnvelopeStage.DECAY
      fsmRunAccum    := True
      when(hardSyncPulse) {
        fsmResetAccum := True
        goto(ATTACK)
      } elsewhen(!gateOn) {
        goto(RELEASE)
      } elsewhen(io.segmentDone) {
        // If Loop Mode (ctrl(2)) is active, loop back to Attack
        when(io.config.ctrl(2)) {
          fsmResetAccum := True
          goto(ATTACK)
        } otherwise {
          goto(SUSTAIN)
        }
      }
    }

    SUSTAIN.whenIsActive {
      fsmActiveStage := EnvelopeStage.SUSTAIN
      when(hardSyncPulse) {
        fsmResetAccum := True
        goto(ATTACK)
      } elsewhen(!gateOn) {
        goto(RELEASE)
      }
    }

    RELEASE.whenIsActive {
      fsmActiveStage := EnvelopeStage.RELEASE
      fsmRunAccum    := True
      when(hardSyncPulse) {
        fsmResetAccum := True
        goto(ATTACK)
      } elsewhen(gateOn) {
        fsmResetAccum := True
        goto(ATTACK)
      } elsewhen(io.segmentDone) {
        fsmResetAccum := True
        goto(IDLE)
      }
    }
  }

  // Gate outputs combinationally with current active reset status
  io.resetAccum  := fsmResetAccum && !ClockDomain.current.isResetActive
  io.runAccum    := fsmRunAccum && !ClockDomain.current.isResetActive
  io.activeStage := ClockDomain.current.isResetActive ? U(EnvelopeStage.IDLE, 3 bits) | fsmActiveStage
}
