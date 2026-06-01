package synth.mixing

import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

class AttenuatorSim extends AnyFunSuite {
  
  // =========================================================================
  // 1. Default 8-bit Configuration Test
  // =========================================================================
  test("Attenuator default 8-bit mathematical scaling and pipeline latency") {
    SimConfig.withWave.compile(new Attenuator).doSim { dut =>
      // 1. Fork the clock and reset using standard forkStimulus
      dut.clockDomain.forkStimulus(period = 10)
      
      // 2. Drive active values and check outputs during initial reset
      println("Verifying Reset Stability during power-on reset:")
      dut.io.sampleIn.valid #= true
      dut.io.sampleIn.payload #= 20000
      dut.io.volume #= 255
      
      // Advance simulation time by 50 units (5 cycles) while reset is active
      sleep(50)
      
      // Assert outputs are strictly quiet/zeroed during reset
      assert(dut.io.sampleOut.payload.toInt == 0, "Output payload must be held at 0 during reset")
      assert(dut.io.sampleOut.valid.toBoolean == false, "Output valid must be held at False during reset")
      
      // 3. Clear inputs and wait for power-on reset to finish deasserting and stabilize
      dut.io.sampleIn.valid #= false
      dut.io.sampleIn.payload #= 0
      dut.io.volume #= 0
      dut.clockDomain.waitSampling(100)
      println("Reset Stability verified successfully.")

      // Helper to check standard mathematical scaling
      def checkScaling(sample: Int, volume: Int, expected: Int): Unit = {
        dut.io.sampleIn.valid #= true
        dut.io.sampleIn.payload #= sample
        dut.io.volume #= volume
        
        dut.clockDomain.waitSampling()
        dut.io.sampleIn.valid #= false
        
        dut.clockDomain.waitSampling()
        // Assert output on the very next cycle (1-cycle pipeline latency)
        assert(dut.io.sampleOut.valid.toBoolean == true, s"Expected output to be valid")
        assert(dut.io.sampleOut.payload.toInt == expected, s"Expected $expected, got ${dut.io.sampleOut.payload.toInt}")
        
        dut.clockDomain.waitSampling()
        assert(dut.io.sampleOut.valid.toBoolean == false, s"Expected output valid to drop to false")
      }

      println("Verifying Attenuator Test Vectors:")
      checkScaling(20000, 255, 19921)
      checkScaling(20000, 128, 10000)
      checkScaling(-20000, 64, -5000)
      checkScaling(-32768, 0, 0)
      println("Test Vectors passed successfully.")

      // Pipelining throughput test (3 consecutive back-to-back samples)
      println("Verifying Pipelined Throughput (Back-to-Back):")
      
      // Cycle 1: Sample 10000, Vol 255
      dut.io.sampleIn.valid #= true
      dut.io.sampleIn.payload #= 10000
      dut.io.volume #= 255
      dut.clockDomain.waitSampling() // Sample 1 is latched. Output 1 is NOT ready yet.
      assert(dut.io.sampleOut.valid.toBoolean == false)

      // Cycle 2: Sample 20000, Vol 128
      dut.io.sampleIn.payload #= 20000
      dut.io.volume #= 128
      dut.clockDomain.waitSampling() // Sample 2 is latched. Output 1 is READY now!
      assert(dut.io.sampleOut.valid.toBoolean == true)
      assert(dut.io.sampleOut.payload.toInt == 9960)

      // Cycle 3: Sample -10000, Vol 64
      dut.io.sampleIn.payload #= -10000
      dut.io.volume #= 64
      dut.clockDomain.waitSampling() // Sample 3 is latched. Output 2 is READY now!
      assert(dut.io.sampleOut.valid.toBoolean == true)
      assert(dut.io.sampleOut.payload.toInt == 10000)

      // Cycle 4: Idle
      dut.io.sampleIn.valid #= false
      dut.clockDomain.waitSampling() // Output 3 is READY now!
      assert(dut.io.sampleOut.valid.toBoolean == true)
      assert(dut.io.sampleOut.payload.toInt == -2500)

      // Cycle 5: Idle
      dut.clockDomain.waitSampling() // Pipeline is empty!
      assert(dut.io.sampleOut.valid.toBoolean == false)

      println("Pipelined Throughput verified successfully.")
    }
  }

  // =========================================================================
  // 2. Parameterized 10-bit Configuration Test
  // =========================================================================
  test("Attenuator parameterized 10-bit mathematical scaling and pipeline latency") {
    SimConfig.withWave.compile(new Attenuator(volumeWidth = 10)).doSim { dut =>
      // 1. Fork the clock and reset using standard forkStimulus
      dut.clockDomain.forkStimulus(period = 10)
      
      // 2. Drive active values and check outputs during initial reset
      println("Verifying 10-bit Reset Stability during power-on reset:")
      dut.io.sampleIn.valid #= true
      dut.io.sampleIn.payload #= 20000
      dut.io.volume #= 1023
      
      // Advance simulation time by 50 units (5 cycles) while reset is active
      sleep(50)
      
      // Assert outputs are strictly quiet/zeroed during reset
      assert(dut.io.sampleOut.payload.toInt == 0, "Output payload must be held at 0 during reset")
      assert(dut.io.sampleOut.valid.toBoolean == false, "Output valid must be held at False during reset")
      
      // 3. Clear inputs and wait for power-on reset to finish deasserting and stabilize
      dut.io.sampleIn.valid #= false
      dut.io.sampleIn.payload #= 0
      dut.io.volume #= 0
      dut.clockDomain.waitSampling(100)
      println("10-bit Reset Stability verified successfully.")

      // Helper to check standard mathematical scaling
      def checkScaling(sample: Int, volume: Int, expected: Int): Unit = {
        dut.io.sampleIn.valid #= true
        dut.io.sampleIn.payload #= sample
        dut.io.volume #= volume
        
        dut.clockDomain.waitSampling()
        dut.io.sampleIn.valid #= false
        
        dut.clockDomain.waitSampling()
        // Assert output on the very next cycle (1-cycle pipeline latency)
        assert(dut.io.sampleOut.valid.toBoolean == true, s"Expected output to be valid")
        assert(dut.io.sampleOut.payload.toInt == expected, s"Expected $expected, got ${dut.io.sampleOut.payload.toInt}")
        
        dut.clockDomain.waitSampling()
        assert(dut.io.sampleOut.valid.toBoolean == false, s"Expected output valid to drop to false")
      }

      println("Verifying 10-bit Attenuator Test Vectors:")
      // Test cases for 10-bit volume (0 to 1023)
      checkScaling(20000, 1023, 19980)  // 20000 * 1023 / 1024 = 19980
      checkScaling(20000, 512, 10000)   // 20000 * 512 / 1024 = 10000
      checkScaling(-20000, 256, -5000)  // -20000 * 256 / 1024 = -5000
      checkScaling(-32768, 0, 0)        // Zero volume
      println("10-bit Test Vectors passed successfully.")

      // Pipelining throughput test (3 consecutive back-to-back samples)
      println("Verifying 10-bit Pipelined Throughput (Back-to-Back):")
      
      // Cycle 1: Sample 10000, Vol 1023
      dut.io.sampleIn.valid #= true
      dut.io.sampleIn.payload #= 10000
      dut.io.volume #= 1023
      dut.clockDomain.waitSampling() // Sample 1 is latched. Output 1 is NOT ready yet.
      assert(dut.io.sampleOut.valid.toBoolean == false)

      // Cycle 2: Sample 20000, Vol 512
      dut.io.sampleIn.payload #= 20000
      dut.io.volume #= 512
      dut.clockDomain.waitSampling() // Sample 2 is latched. Output 1 is READY now!
      assert(dut.io.sampleOut.valid.toBoolean == true)
      assert(dut.io.sampleOut.payload.toInt == 9990) // 10000 * 1023 / 1024 = 9990

      // Cycle 3: Sample -10000, Vol 256
      dut.io.sampleIn.payload #= -10000
      dut.io.volume #= 256
      dut.clockDomain.waitSampling() // Sample 3 is latched. Output 2 is READY now!
      assert(dut.io.sampleOut.valid.toBoolean == true)
      assert(dut.io.sampleOut.payload.toInt == 10000) // 20000 * 512 / 1024 = 10000

      // Cycle 4: Idle
      dut.io.sampleIn.valid #= false
      dut.clockDomain.waitSampling() // Output 3 is READY now!
      assert(dut.io.sampleOut.valid.toBoolean == true)
      assert(dut.io.sampleOut.payload.toInt == -2500) // -10000 * 256 / 1024 = -2500

      // Cycle 5: Idle
      dut.clockDomain.waitSampling() // Pipeline is empty!
      assert(dut.io.sampleOut.valid.toBoolean == false)

      println("10-bit Pipelined Throughput verified successfully.")
    }
  }
}
