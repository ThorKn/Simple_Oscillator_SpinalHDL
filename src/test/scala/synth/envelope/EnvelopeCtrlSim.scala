package synth.envelope

import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite
import synth.common.EnvelopeConfig

class EnvelopeCtrlSim extends AnyFunSuite {

  test("EnvelopeCtrl unit test - FSM state transitions & Reset") {
    SimConfig.withWave.compile(new EnvelopeCtrl).doSim { dut =>
      
      // 1. Initialize clock and assert reset using standard forkStimulus
      dut.clockDomain.forkStimulus(period = 10)

      // Initialize inputs to safe defaults
      dut.io.syncIn #= false
      dut.io.midiClock #= false
      dut.io.config.ctrl #= 0
      dut.io.config.attack #= 0
      dut.io.config.decay #= 0
      dut.io.config.sustain #= 0
      dut.io.config.release #= 0
      dut.io.config.syncCtrl #= 0
      dut.io.config.phaseOffset #= 0
      dut.io.segmentDone #= false

      // Wait 1 clock cycle to stabilize signals under active reset
      dut.clockDomain.waitSampling()

      // ---------------------------------------------------------------------
      // 1.1.1 Reset Stability
      // ---------------------------------------------------------------------
      println("Verifying EnvelopeCtrl Reset Stability:")
      
      // Verify outputs remain strictly quiet during the natural active reset phase
      assert(!dut.io.resetAccum.toBoolean, "resetAccum must remain False under reset")
      assert(!dut.io.runAccum.toBoolean, "runAccum must remain False under reset")
      assert(dut.io.activeStage.toInt == 0, "activeStage must remain 0 (IDLE) under reset")
      
      // Wait for the startup reset (forkStimulus) to automatically complete (takes 15 cycles)
      dut.clockDomain.waitSampling(20)
      println("EnvelopeCtrl Reset Stability verified successfully.")

      // ---------------------------------------------------------------------
      // 1.1.2 Normal ADSR State Transitions
      // ---------------------------------------------------------------------
      println("Verifying Normal ADSR State Transitions:")
      
      // Step 1: Trigger Gate ON (config.ctrl[1] is Gate bit -> value = 2)
      dut.io.config.ctrl #= 2
      dut.clockDomain.waitSampling(2)
      
      // IDLE -> ATTACK (activeStage = 1)
      assert(dut.io.activeStage.toInt == 1, s"Expected activeStage 1 (ATTACK) after Gate ON, got ${dut.io.activeStage.toInt}")
      assert(dut.io.runAccum.toBoolean, "Accumulator should run during ATTACK stage")

      // Step 2: Pulse segmentDone to transition to DECAY
      dut.io.segmentDone #= true
      dut.clockDomain.waitSampling()
      dut.io.segmentDone #= false
      dut.clockDomain.waitSampling()

      // ATTACK -> DECAY (activeStage = 2)
      assert(dut.io.activeStage.toInt == 2, s"Expected activeStage 2 (DECAY) after Attack done, got ${dut.io.activeStage.toInt}")
      assert(dut.io.runAccum.toBoolean, "Accumulator should run during DECAY stage")

      // Step 3: Pulse segmentDone to transition to SUSTAIN
      dut.io.segmentDone #= true
      dut.clockDomain.waitSampling()
      dut.io.segmentDone #= false
      dut.clockDomain.waitSampling()

      // DECAY -> SUSTAIN (activeStage = 3)
      assert(dut.io.activeStage.toInt == 3, s"Expected activeStage 3 (SUSTAIN) after Decay done, got ${dut.io.activeStage.toInt}")
      assert(!dut.io.runAccum.toBoolean, "Accumulator must pause during SUSTAIN stage")

      // Step 4: Toggle Gate OFF (value = 0)
      dut.io.config.ctrl #= 0
      dut.clockDomain.waitSampling(2)

      // SUSTAIN -> RELEASE (activeStage = 4)
      assert(dut.io.activeStage.toInt == 4, s"Expected activeStage 4 (RELEASE) after Gate OFF, got ${dut.io.activeStage.toInt}")
      assert(dut.io.runAccum.toBoolean, "Accumulator should run during RELEASE stage")

      // Step 5: Pulse segmentDone to transition back to IDLE
      dut.io.segmentDone #= true
      dut.clockDomain.waitSampling()
      dut.io.segmentDone #= false
      dut.clockDomain.waitSampling()

      // RELEASE -> IDLE (activeStage = 0)
      assert(dut.io.activeStage.toInt == 0, s"Expected activeStage 0 (IDLE) after Release done, got ${dut.io.activeStage.toInt}")
      assert(!dut.io.runAccum.toBoolean, "Accumulator must pause in IDLE")
      
      println("Normal ADSR State Transitions verified successfully.")

      // ---------------------------------------------------------------------
      // 1.1.3 Gate Interruption & Re-triggering
      // ---------------------------------------------------------------------
      println("Verifying Gate Interruption and Re-triggering Dynamics:")
      
      // Case A: ATTACK -> Gate OFF -> RELEASE
      dut.io.config.ctrl #= 2 // Gate ON
      dut.clockDomain.waitSampling(2)
      assert(dut.io.activeStage.toInt == 1, "Should be in ATTACK")
      
      dut.io.config.ctrl #= 0 // Gate OFF mid-flight
      dut.clockDomain.waitSampling(2)
      assert(dut.io.activeStage.toInt == 4, s"Expected direct transition to RELEASE (4) on Gate OFF, got ${dut.io.activeStage.toInt}")

      // Case B: RELEASE -> Gate ON -> ATTACK
      dut.io.config.ctrl #= 2 // Gate ON mid-release
      dut.clockDomain.waitSampling(2)
      assert(dut.io.activeStage.toInt == 1, s"Expected immediate re-trigger to ATTACK (1) from RELEASE, got ${dut.io.activeStage.toInt}")
      
      // Clear back to IDLE
      dut.io.config.ctrl #= 0
      dut.clockDomain.waitSampling(2)
      dut.io.segmentDone #= true
      dut.clockDomain.waitSampling()
      dut.io.segmentDone #= false
      dut.clockDomain.waitSampling()
      assert(dut.io.activeStage.toInt == 0)
      
      println("Gate Interruption and Re-triggering Dynamics verified successfully.")

      // ---------------------------------------------------------------------
      // 1.1.4 Looping (LFO) Mode
      // ---------------------------------------------------------------------
      println("Verifying LFO Looping Playback:")
      
      // Set Loop Enable (ctrl[2] = 4) + Gate ON (ctrl[1] = 2) -> value = 6
      dut.io.config.ctrl #= 6
      dut.clockDomain.waitSampling(2)
      assert(dut.io.activeStage.toInt == 1, "Expected FSM in ATTACK")

      // Complete Attack -> enter Decay
      dut.io.segmentDone #= true
      dut.clockDomain.waitSampling()
      dut.io.segmentDone #= false
      dut.clockDomain.waitSampling()
      assert(dut.io.activeStage.toInt == 2, "Expected FSM in DECAY")

      // Complete Decay -> should loop back to Attack
      dut.io.segmentDone #= true
      dut.clockDomain.waitSampling()
      dut.io.segmentDone #= false
      dut.clockDomain.waitSampling()
      assert(dut.io.activeStage.toInt == 1, s"Expected FSM to loop back to ATTACK (1) in LFO mode, got ${dut.io.activeStage.toInt}")
      
      // Clear control registers and idle FSM
      dut.io.config.ctrl #= 0
      dut.clockDomain.waitSampling(2)
      println("LFO Looping Playback verified successfully.")

      // ---------------------------------------------------------------------
      // 1.1.5 Logarithmic Increment ROM Mapping
      // ---------------------------------------------------------------------
      println("Verifying Logarithmic Rate Coefficient ROM Mapping:")
      
      // Force FSM into ATTACK state to map attack ROM values
      dut.io.config.ctrl #= 2
      dut.clockDomain.waitSampling(2)
      
      // Check minimum speed mapping (Attack = 0 -> T_min = 0.5 ms)
      dut.io.config.attack #= 0
      dut.clockDomain.waitSampling(2)
      val actualMinInc = dut.io.phaseInc.toLong
      assert(actualMinInc == 357914L, s"Expected phaseInc constant 357914 for attack = 0, got $actualMinInc")

      // Check maximum speed mapping (Attack = 255 -> T_max = 30 s)
      dut.io.config.attack #= 255
      dut.clockDomain.waitSampling(2)
      val actualMaxInc = dut.io.phaseInc.toLong
      assert(actualMaxInc == 6L, s"Expected phaseInc constant 6 for attack = 255, got $actualMaxInc")
      
      println("Logarithmic Rate Coefficient ROM Mapping verified successfully.")
    }
  }
}
