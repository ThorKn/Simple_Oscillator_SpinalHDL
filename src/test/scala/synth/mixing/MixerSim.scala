package synth.mixing

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._

class MixerSim extends AnyFunSuite {

  test("Mixer unit test - summing, clamping, muting, and timing") {
    SimConfig.withWave.compile(new Mixer(numVoices = 3)).doSim { dut =>
      // Start clock
      dut.clockDomain.forkStimulus(period = 10)

      // Initialize inputs
      for (i <- 0 until 3) {
        dut.io.inputs(i).valid #= false
        dut.io.inputs(i).payload #= 0
      }
      dut.io.mixerCtrl #= 0x00 // Default: all un-muted (ON)
      dut.io.phaseTick #= false

      // Wait for power-on reset to finish and stabilize
      dut.clockDomain.waitSampling(100)

      // ==========================================
      // Test case 1: Normal summation (no clamping)
      // ==========================================
      dut.io.inputs(0).payload #= 1000
      dut.io.inputs(1).payload #= -2000
      dut.io.inputs(2).payload #= 500
      
      // Assert valid and phaseTick together to load the inputs and trigger validReg
      for (i <- 0 until 3) dut.io.inputs(i).valid #= true
      dut.io.phaseTick #= true
      dut.clockDomain.waitSampling()

      // Deassert valid and phaseTick
      for (i <- 0 until 3) dut.io.inputs(i).valid #= false
      dut.io.phaseTick #= false
      dut.clockDomain.waitSampling(5) // Wait a few clock cycles

      // Next phaseTick: valid output is active, holding the buffered sum
      dut.io.phaseTick #= true
      sleep(1) // Let combinational logic propagate by advancing 1 time unit
      println(s"DEBUG Test Case 1: phaseTick = ${dut.io.phaseTick.toBoolean}, validReg = ${dut.validReg.toBoolean}, sampleOut.valid = ${dut.io.sampleOut.valid.toBoolean}, sampleOut.payload = ${dut.io.sampleOut.payload.toInt}")
      assert(dut.io.sampleOut.payload.toInt == -500, s"Expected output -500, got ${dut.io.sampleOut.payload.toInt}")
      assert(dut.io.sampleOut.valid.toBoolean == true)
      dut.clockDomain.waitSampling()
      dut.io.phaseTick #= false
      dut.clockDomain.waitSampling()

      // ==========================================
      // Test case 2: Saturation Clamping (Positive Overflow)
      // ==========================================
      dut.io.inputs(0).payload #= 20000
      dut.io.inputs(1).payload #= 15000
      dut.io.inputs(2).payload #= 5000 // Total 40000 (> 32767)

      for (i <- 0 until 3) dut.io.inputs(i).valid #= true
      dut.io.phaseTick #= true
      dut.clockDomain.waitSampling()

      for (i <- 0 until 3) dut.io.inputs(i).valid #= false
      dut.io.phaseTick #= false
      dut.clockDomain.waitSampling(5)

      dut.io.phaseTick #= true
      sleep(1)
      assert(dut.io.sampleOut.payload.toInt == 32767, s"Expected positive clamped output 32767, got ${dut.io.sampleOut.payload.toInt}")
      assert(dut.io.sampleOut.valid.toBoolean == true)
      dut.clockDomain.waitSampling()
      dut.io.phaseTick #= false
      dut.clockDomain.waitSampling()

      // ==========================================
      // Test case 3: Saturation Clamping (Negative Overflow)
      // ==========================================
      dut.io.inputs(0).payload #= -20000
      dut.io.inputs(1).payload #= -15000
      dut.io.inputs(2).payload #= -5000 // Total -40000 (< -32768)

      for (i <- 0 until 3) dut.io.inputs(i).valid #= true
      dut.io.phaseTick #= true
      dut.clockDomain.waitSampling()

      for (i <- 0 until 3) dut.io.inputs(i).valid #= false
      dut.io.phaseTick #= false
      dut.clockDomain.waitSampling(5)

      dut.io.phaseTick #= true
      sleep(1)
      assert(dut.io.sampleOut.payload.toInt == -32768, s"Expected negative clamped output -32768, got ${dut.io.sampleOut.payload.toInt}")
      assert(dut.io.sampleOut.valid.toBoolean == true)
      dut.clockDomain.waitSampling()
      dut.io.phaseTick #= false
      dut.clockDomain.waitSampling()

      // ==========================================
      // Test case 4: Mute / Disable Logic (Bitmask 0x05 / binary 101)
      // ==========================================
      // Mute Voice 0 and Voice 2 (bits 0 and 2 set to 1) -> Only Voice 1 remains active
      dut.io.mixerCtrl #= 0x05

      dut.io.inputs(0).payload #= 10000 // Muted
      dut.io.inputs(1).payload #= 4500  // Active
      dut.io.inputs(2).payload #= 20000 // Muted

      for (i <- 0 until 3) dut.io.inputs(i).valid #= true
      dut.io.phaseTick #= true
      dut.clockDomain.waitSampling()

      for (i <- 0 until 3) dut.io.inputs(i).valid #= false
      dut.io.phaseTick #= false
      dut.clockDomain.waitSampling(5)

      dut.io.phaseTick #= true
      sleep(1)
      // Expected output: only Voice 1 contribution = 4500
      assert(dut.io.sampleOut.payload.toInt == 4500, s"Expected output 4500, got ${dut.io.sampleOut.payload.toInt}")
      assert(dut.io.sampleOut.valid.toBoolean == true)
      dut.clockDomain.waitSampling()
      dut.io.phaseTick #= false
    }
  }
}
