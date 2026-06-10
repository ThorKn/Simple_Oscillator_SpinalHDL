package synth.envelope

import spinal.core._
import spinal.lib._
import spinal.lib.fsm._
import synth.common.{EnvelopeConfig, EnvelopeStage, RomData}

class EnvelopeCtrl extends Component {
  val io = new Bundle {
    // Inputs
    val syncIn      = in Bool()
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

  // Gate input is mapped to bit 0 of the gate register
  val gateOn = io.config.gate(0)

  // -------------------------------------------------------------------------
  // Logarithmic Increment ROM Mapping (256 words x 22 bits)
  // -------------------------------------------------------------------------
  val rom = Mem(UInt(22 bits), 256) init(RomData.envelopeRateLut.map(U(_, 22 bits)))

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
  // Curve Select: ctrl[5:4]
  io.curveSelect := io.config.ctrl(5 downto 4).asUInt

  val disable = io.config.ctrl(0)

  // Hard Sync: Rising edge detection on syncIn (hardware) or gate(1) (software) qualified with ctrl[3] (Hard Sync Enable) and reset inactive
  val syncInD1 = RegNext(io.syncIn) init(false)
  val hwSyncPulse = io.syncIn && !syncInD1

  val swSyncD1 = RegNext(io.config.gate(1)) init(false)
  val swSyncPulse = io.config.gate(1) && !swSyncD1

  val hardSyncPulse = (hwSyncPulse || swSyncPulse) && io.config.ctrl(3) && !ClockDomain.current.isResetActive

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
      when(gateOn && !disable) {
        fsmResetAccum := True
        fsmRunAccum   := True
        goto(ATTACK)
      }
    }

    ATTACK.whenIsActive {
      fsmActiveStage := EnvelopeStage.ATTACK
      fsmRunAccum    := True
      when(disable) {
        fsmResetAccum := True
        goto(IDLE)
      } elsewhen(hardSyncPulse) {
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
      when(disable) {
        fsmResetAccum := True
        goto(IDLE)
      } elsewhen(hardSyncPulse) {
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
      when(disable) {
        fsmResetAccum := True
        goto(IDLE)
      } elsewhen(hardSyncPulse) {
        fsmResetAccum := True
        goto(ATTACK)
      } elsewhen(!gateOn) {
        goto(RELEASE)
      }
    }

    RELEASE.whenIsActive {
      fsmActiveStage := EnvelopeStage.RELEASE
      fsmRunAccum    := True
      when(disable) {
        fsmResetAccum := True
        goto(IDLE)
      } elsewhen(hardSyncPulse) {
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
