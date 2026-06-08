package synth.filter

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.fsm._


class FilterCore extends Component {
  val io = new Bundle {
    val phaseTick      = in Bool()
    val clear          = in Bool()
    val sampleIn       = in SInt(16 bits)
    val cutoffCoeff    = in UInt(12 bits)
    val resonanceCoeff = in UInt(8 bits)

    val lp             = out SInt(24 bits)
    val bp             = out SInt(24 bits)
    val hp             = out SInt(24 bits)
    val done           = out Bool()
  }

  // State Registers
  val lpReg = Reg(SInt(24 bits)).init(0).simPublic
  val bpReg = Reg(SInt(24 bits)).init(0).simPublic

  val hpReg = Reg(SInt(24 bits)) init(0)
  val sampleInReg = Reg(SInt(16 bits)) init(0)


  // Intermediate Registers
  val resTerm = Reg(SInt(24 bits)) init(0)
  val tempSub = Reg(SInt(24 bits)) init(0)
  val bpTerm  = Reg(SInt(24 bits)) init(0)
  val lpTerm  = Reg(SInt(24 bits)) init(0)

  // 1. Shared Multiplier Inputs & Output
  val multIn1 = SInt(24 bits)
  val multIn2 = SInt(24 bits)
  multIn1 := 0
  multIn2 := 0
  val multOut = multIn1 * multIn2 // SInt 48 bits

  // 2. Shared Adder Inputs & Output
  val addIn1 = SInt(24 bits)
  val addIn2 = SInt(24 bits)
  val isSub  = Bool()
  addIn1 := 0
  addIn2 := 0
  isSub  := False
  val addOut = isSub ? (addIn1 - addIn2) | (addIn1 + addIn2) // SInt 25 bits
  val addOutResized = addOut.resize(24 bits)

  // 3. FSM Sequencer State Machine
  io.done := False // Default value
  
  val fsm = new StateMachine {
    val IDLE         = new State with EntryPoint
    val CALC_RES     = new State
    val SUB_INPUT    = new State
    val CALC_HP      = new State
    val CALC_BP_TERM = new State
    val UPDATE_BP    = new State
    val CALC_LP_TERM = new State
    val UPDATE_LP    = new State

    // Global clear override inside the FSM scope
    always {
      when(io.clear) {
        goto(IDLE)
      }
    }

    IDLE.whenIsActive {
      when(io.phaseTick) {
        sampleInReg := io.sampleIn
        goto(CALC_RES)
      }
    }

    CALC_RES.whenIsActive {
      multIn1 := bpReg
      multIn2 := io.resonanceCoeff.intoSInt.resize(24 bits)
      resTerm := (multOut >> 8).resize(24 bits)
      goto(SUB_INPUT)
    }

    SUB_INPUT.whenIsActive {
      addIn1 := sampleInReg.resize(24 bits)
      addIn2 := lpReg
      isSub  := True
      tempSub := addOutResized
      goto(CALC_HP)
    }


    CALC_HP.whenIsActive {
      addIn1 := tempSub
      addIn2 := resTerm
      isSub  := True
      hpReg  := addOutResized
      goto(CALC_BP_TERM)
    }

    CALC_BP_TERM.whenIsActive {
      multIn1 := hpReg
      multIn2 := io.cutoffCoeff.intoSInt.resize(24 bits)
      bpTerm  := (multOut >> 12).resize(24 bits)
      goto(UPDATE_BP)
    }

    UPDATE_BP.whenIsActive {
      addIn1 := bpReg
      addIn2 := bpTerm
      isSub  := False
      bpReg  := addOutResized
      goto(CALC_LP_TERM)
    }

    CALC_LP_TERM.whenIsActive {
      multIn1 := bpReg
      multIn2 := io.cutoffCoeff.intoSInt.resize(24 bits)
      lpTerm  := (multOut >> 12).resize(24 bits)
      goto(UPDATE_LP)
    }


    UPDATE_LP.whenIsActive {
      addIn1 := lpReg
      addIn2 := lpTerm
      isSub  := False
      lpReg  := addOutResized
      io.done := True
      goto(IDLE)
    }
  }

  // 4. Clear state variables when disabled
  when(io.clear) {
    lpReg := 0
    bpReg := 0
    hpReg := 0
  }

  // 5. Outputs
  io.lp   := lpReg
  io.bp   := bpReg
  io.hp   := hpReg
}


