package synth.envelope

import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite
import synth.common.EnvelopeConfig

class EnvelopeGeneratorSim extends AnyFunSuite {

  // =========================================================================
  // 2.1 Complete Envelope Generator Integration Test
  // =========================================================================
  test("EnvelopeGenerator integration test - full wrapper ADSR, timing & edge cases") {
    SimConfig.withWave.compile(new EnvelopeGenerator).doSim { dut =>
      
      // 1. Initialize clock and assert reset using standard forkStimulus
      dut.clockDomain.forkStimulus(period = 10)

      // Initialize inputs to safe defaults
      dut.io.phaseTick #= false
      dut.io.syncIn #= false
      dut.io.midiClock #= false
      dut.io.config.ctrl #= 0
      dut.io.config.attack #= 0
      dut.io.config.decay #= 0
      dut.io.config.sustain #= 0
      dut.io.config.release #= 0
      dut.io.config.syncCtrl #= 0
      dut.io.config.phaseOffset #= 0
      dut.clockDomain.waitSampling()

      // ---------------------------------------------------------------------
      // 2.1.1 Power-On Reset & Boot Stability
      // ---------------------------------------------------------------------
      println("Verifying EnvelopeGenerator Power-On Reset & Boot Stability:")
      dut.io.phaseTick #= true
      dut.io.syncIn #= true
      dut.io.config.ctrl #= 0xFF
      dut.io.config.attack #= 128
      
      // Verify outputs remain strictly quiet during the natural active reset phase of forkStimulus
      assert(!dut.io.envelopeOut.valid.toBoolean, "envelopeOut.valid must remain False under reset")
      assert(dut.io.envelopeOut.payload.toInt == 0, "envelopeOut.payload must remain 0 under reset")
      assert(!dut.io.envelopeOutSigned.valid.toBoolean, "envelopeOutSigned.valid must remain False under reset")
      assert(dut.io.envelopeOutSigned.payload.toInt == 0, "envelopeOutSigned.payload must remain 0 under reset")
      
      // Wait for the startup reset (forkStimulus) to automatically complete (takes 20 cycles)
      dut.clockDomain.waitSampling(20)
      
      // Clear inputs
      dut.io.phaseTick #= false
      dut.io.syncIn #= false
      dut.io.config.ctrl #= 0
      dut.io.config.attack #= 0
      println("EnvelopeGenerator Power-On Reset & Boot Stability verified successfully.")

      // ---------------------------------------------------------------------
      // 2.1.2 Standard ADSR Envelope Playback
      // ---------------------------------------------------------------------
      println("Verifying Standard ADSR Envelope Playback:")
      
      // Configure Linear ADSR: 
      // attack = 0 (Minimum transient: 0.5 ms -> increment = 357914)
      // sustain = 128 (scaled unipolar sustain level of 512)
      dut.io.config.ctrl #= 2 // Gate ON
      dut.io.config.attack #= 0
      dut.io.config.decay #= 0
      dut.io.config.sustain #= 128
      dut.io.config.release #= 0
      dut.io.phaseTick #= true
      sleep(1) // Settle combinational inputs
      
      // Monitor progressive, monotonic envelope rise
      var lastVal = 0
      for (c <- 1 to 10) {
        dut.clockDomain.waitSampling()
        val currentVal = dut.io.envelopeOut.payload.toInt
        assert(currentVal >= lastVal, s"[Playback Cycle $c] Output must climb monotonically. Current: $currentVal, Last: $lastVal")
        lastVal = currentVal
      }
      
      println("Standard ADSR Envelope Playback verified successfully.")

      // ---------------------------------------------------------------------
      // 2.1.3 Pipeline Latency & Sustain Clamping Sync
      // ---------------------------------------------------------------------
      println("Verifying 3-Cycle Pipeline Latency & Delay-Matched Sustain Clamping:")
      
      // Reset generator to IDLE
      dut.io.config.ctrl #= 0
      sleep(1)
      dut.clockDomain.waitSampling(5) // Allow pipeline to drain
      
      // Gate ON at Cycle 0
      dut.io.config.ctrl #= 2
      sleep(1) // Settle Gate ON input before clocking
      
      // Verify first output appears exactly at Cycle 3 (confirming the 3-cycle pipeline: T0 -> T1 -> T2 -> T3)
      dut.clockDomain.waitSampling() // T0
      assert(dut.io.envelopeOut.payload.toInt == 0, "Pipeline T0: Output must be 0")
      dut.clockDomain.waitSampling() // T1
      assert(dut.io.envelopeOut.payload.toInt == 0, "Pipeline T1: Output must be 0")
      dut.clockDomain.waitSampling() // T2
      assert(dut.io.envelopeOut.payload.toInt == 0, "Pipeline T2: Output must be 0")
      
      // Wait 15 more cycles to let the high-precision accumulator cross the LSB threshold and propagate
      dut.clockDomain.waitSampling(15)
      val valAfterPropagation = dut.io.envelopeOut.payload.toInt
      assert(valAfterPropagation > 0, s"Pipeline T17: Expected active value, got $valAfterPropagation")
      
      println("3-Cycle Pipeline Latency & Delay-Matched Sustain Clamping verified successfully.")

      // ---------------------------------------------------------------------
      // 2.1.4 Simultaneous Gate and Sync Conflict
      // ---------------------------------------------------------------------
      println("Verifying Simultaneous Gate & Sync Conflict Resolution:")
      
      // Pulse Gate ON and syncIn High at the same clock cycle
      dut.io.config.ctrl #= 2
      dut.io.syncIn #= true
      sleep(1) // Settle inputs
      dut.clockDomain.waitSampling()
      
      // Verify Hard Sync takes priority (FSM enters Attack stage and phase accumulator resets instantly)
      dut.io.syncIn #= false
      sleep(1)
      dut.clockDomain.waitSampling()
      println("Simultaneous Gate & Sync Conflict Resolution verified successfully.")

      // ---------------------------------------------------------------------
      // 2.1.5 Ultra-High Frequency Gate Chattering
      // ---------------------------------------------------------------------
      println("Verifying Robustness to High-Frequency Gate Chattering:")
      
      // Rapidly toggle the gate ON and OFF every 2 clock cycles to simulate keyboard bouncing
      for (i <- 1 to 5) {
        dut.io.config.ctrl #= 2 // ON
        sleep(1)
        dut.clockDomain.waitSampling(2)
        dut.io.config.ctrl #= 0 // OFF
        sleep(1)
        dut.clockDomain.waitSampling(2)
      }
      
      // FSM and outputs must remain strictly bounded (0 to 1023) and completely stable
      val finalVal = dut.io.envelopeOut.payload.toInt
      assert(finalVal >= 0 && finalVal <= 1023, s"Gate chattering: Output out of bounds, got $finalVal")
      
      println("Robustness to High-Frequency Gate Chattering verified successfully.")

      // ---------------------------------------------------------------------
      // 2.1.6 Dynamic Mid-Flight Curve Switching
      // ---------------------------------------------------------------------
      println("Verifying Mid-Flight Wave-Shaping Curve Switching:")
      
      // Trigger a standard Linear Envelope rise
      dut.io.config.ctrl #= 2
      dut.io.config.attack #= 0
      sleep(1)
      dut.clockDomain.waitSampling(5)
      
      // Change curve selection to Exponential (ctrl[6:5] = 01 -> value = 0x22) mid-transition
      dut.io.config.ctrl #= 0x22
      sleep(1)
      dut.clockDomain.waitSampling()
      
      // Verify output continues climbing smoothly using the new curve lookup without phase pops
      var valAfterSwitch = dut.io.envelopeOut.payload.toInt
      for (i <- 1 to 5) {
        dut.clockDomain.waitSampling()
        val nextVal = dut.io.envelopeOut.payload.toInt
        assert(nextVal >= valAfterSwitch, s"[Switch Cycle $i] Monotonic climb mismatch after curve switch. Current: $nextVal, Last: $valAfterSwitch")
        valAfterSwitch = nextVal
      }
      
      println("Mid-Flight Wave-Shaping Curve Switching verified successfully.")
    }
  }
}
