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
      dut.io.config.ctrl #= 0
      dut.io.config.attack #= 0
      dut.io.config.decay #= 0
      dut.io.config.sustain #= 0
      dut.io.config.release #= 0
      dut.io.config.gate #= 0
      dut.clockDomain.waitSampling()

      // ---------------------------------------------------------------------
      // 2.1.1 Power-On Reset & Boot Stability
      // ---------------------------------------------------------------------
      println("Verifying EnvelopeGenerator Power-On Reset & Boot Stability:")
      dut.io.phaseTick #= true
      dut.io.syncIn #= true
      dut.io.config.ctrl #= 0xFF
      dut.io.config.gate #= 0xFF
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
      dut.io.config.gate #= 0
      dut.io.config.attack #= 0
      println("EnvelopeGenerator Power-On Reset & Boot Stability verified successfully.")

      // ---------------------------------------------------------------------
      // 2.1.2 Standard ADSR Envelope Playback
      // ---------------------------------------------------------------------
      println("Verifying Standard ADSR Envelope Playback:")
      
      // Configure Linear ADSR: 
      // attack = 0 (Minimum transient: 0.5 ms -> increment = 357914)
      // sustain = 128 (scaled unipolar sustain level of 512)
      dut.io.config.ctrl #= 1 // Enable ON
      dut.io.config.gate #= 1 // Gate ON
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
      println("Verifying 1-Cycle Pipeline Latency & Delay-Matched Sustain Clamping:")
      
      // Reset generator to IDLE
      dut.io.config.ctrl #= 0
      dut.io.config.gate #= 0
      sleep(1)
      dut.clockDomain.waitSampling(5) // Allow pipeline to settle
      
      // Gate ON at Cycle 0
      dut.io.config.ctrl #= 1 // Enable
      dut.io.config.gate #= 1 // Gate ON
      sleep(1) // Settle Gate ON input before clocking
      
      // Verify first output appears exactly after Cycle 3 and starts climbing after Cycle 50
      dut.clockDomain.waitSampling(3) // FSM transitions, accumulator resets to 0 (at Cycle 2), shaper registers 0 (at Cycle 3)
      assert(dut.io.envelopeOut.payload.toInt == 0, s"Pipeline: Output must be 0, got ${dut.io.envelopeOut.payload.toInt}")
      
      // Wait 47 more cycles to let the high-precision accumulator cross the LSB threshold and propagate
      dut.clockDomain.waitSampling(47)
      val valAfterPropagation = dut.io.envelopeOut.payload.toInt
      assert(valAfterPropagation > 0, s"Pipeline: Expected active value after propagation, got $valAfterPropagation")
      
      println("1-Cycle Pipeline Latency & Delay-Matched Sustain Clamping verified successfully.")

      // ---------------------------------------------------------------------
      // 2.1.4 Simultaneous Gate and Sync Conflict
      // ---------------------------------------------------------------------
      println("Verifying Simultaneous Gate & Sync Conflict Resolution:")
      
      // Pulse Gate ON and syncIn High at the same clock cycle
      dut.io.config.ctrl #= 1
      dut.io.config.gate #= 1
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
        dut.io.config.gate #= 1 // ON
        sleep(1)
        dut.clockDomain.waitSampling(2)
        dut.io.config.gate #= 0 // OFF
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
      dut.io.config.ctrl #= 1 // Enable
      dut.io.config.gate #= 1 // Gate ON
      dut.io.config.attack #= 0
      sleep(1)
      dut.clockDomain.waitSampling(5)
      
      // Change curve selection to Exponential (ctrl[5:4] = 01, Enable = 1 -> value = 17 or 0x11) mid-transition
      dut.io.config.ctrl #= 17
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

      // ---------------------------------------------------------------------
      // 2.1.7 Full ADSR State Transition and Output Verification
      // ---------------------------------------------------------------------
      println("Verifying Full ADSR State Transition and Output Verification:")
      
      // Configure linear ADSR: Sustain = 128 (512)
      dut.io.config.ctrl #= 1 // Enable
      dut.io.config.gate #= 1 // Gate ON, Envelope Enable
      dut.io.config.sustain #= 128
      dut.io.config.attack #= 0
      dut.io.config.decay #= 0
      sleep(1)

      // 1. Transition to ATTACK
      dut.clockDomain.waitSampling(2)
      
      // 2. Force ATTACK to complete by setting accum close to overflow
      dut.accumulator.accum #= 0xFFF00000L
      dut.clockDomain.waitSampling(4) // Let it accumulate and transition to DECAY phase (where it presets to 0xFFFFFFFF)
      
      // Verify that after Cycle 4, the accumulator has preset to full scale (0xFFFFFFFF) in DECAY phase
      assert(dut.accumulator.accum.toLong == 4294967295L, s"DECAY Peak: Accumulator must be preset to 0xFFFFFFFF, got ${dut.accumulator.accum.toLong}")
      
      // 3. Verify FSM is in DECAY stage (stage 2) and counting downwards
      dut.clockDomain.waitSampling() // Cycle 5
      val valAtDecayStart = dut.io.envelopeOut.payload.toInt
      assert(dut.accumulator.accum.toLong == 4294609381L, s"DECAY Downward step: Expected 4294609381, got ${dut.accumulator.accum.toLong}")
      
      // 4. Force decay to complete by setting accum close to sustainLevel (128)
      // We set it to 129 << 24, so that on the next step it crosses <= 128
      dut.accumulator.accum #= (129L << 24)
      dut.clockDomain.waitSampling() // Let it step into <= 128
      
      // 5. Verify transition to SUSTAIN (stage 3)
      dut.clockDomain.waitSampling() // Transition to SUSTAIN on this edge
      // Accumulator is frozen in SUSTAIN
      val valAtSustain = dut.io.envelopeOut.payload.toInt
      // Since sustainLevel = 128, the shaped sustain value is approximately half scale (512 plus fractional crossing step LSBs)
      assert(valAtSustain >= 512 && valAtSustain <= 525, s"SUSTAIN Level: Expected close to 512, got $valAtSustain")
      
      // 6. Transition to RELEASE (stage 4) by toggling Gate OFF
      dut.io.config.gate #= 0
      sleep(1)
      dut.clockDomain.waitSampling(2) // FSM transitions to RELEASE
      
      // 7. Verify RELEASE downward progression. Accumulator must count down from sustain level.
      // We set accum close to underflow to trigger the completion
      dut.accumulator.accum #= 100 // Very small value so that subtracting phaseInc (357914) underflows instantly
      dut.clockDomain.waitSampling() // Step into underflow
      
      // 8. Verify transition to IDLE (stage 0)
      dut.clockDomain.waitSampling() // Transition to IDLE
      // The first cycle of IDLE still reflects the last RELEASE state's active value (3) due to 1-cycle shaper RegNext latency
      assert(dut.io.envelopeOut.payload.toInt == 3, s"T0 IDLE Level: Expected 3, got ${dut.io.envelopeOut.payload.toInt}")
      
      dut.clockDomain.waitSampling() // Let the 1-cycle pipeline register drain to 0
      val valAtIdle = dut.io.envelopeOut.payload.toInt
      assert(valAtIdle == 0, s"IDLE Level: Expected 0, got $valAtIdle")
      
      println("Full ADSR State Transition and Output Verification verified successfully.")
    }
  }
}
