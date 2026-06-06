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
      dut.io.phaseTick #= false
      
      // Advance simulation time by 50 units (5 cycles) while reset is active
      sleep(50)
      
      // Assert outputs are strictly quiet/zeroed during reset
      assert(dut.io.sampleOut.payload.toInt == 0, "Output payload must be held at 0 during reset")
      assert(dut.io.sampleOut.valid.toBoolean == false, "Output valid must be held at False during reset")
      
      // 3. Clear inputs and wait for power-on reset to finish deasserting and stabilize
      dut.io.sampleIn.valid #= false
      dut.io.sampleIn.payload #= 0
      dut.io.volume #= 0
      dut.io.phaseTick #= false
      dut.clockDomain.waitSampling(100)
      println("Reset Stability verified successfully.")

      // Helper to check standard mathematical scaling with phaseTick-alignment
      def checkScaling(sample: Int, volume: Int, expected: Int): Unit = {
        // Start of period: Assert phaseTick and valid input sample
        dut.io.phaseTick #= true
        dut.io.sampleIn.valid #= true
        dut.io.sampleIn.payload #= sample
        dut.io.volume #= volume
        dut.clockDomain.waitSampling()

        dut.io.phaseTick #= false
        dut.io.sampleIn.valid #= false
        dut.io.sampleIn.payload #= 0

        var validAssertCount = 0
        var cyclesToValid = 0
        var observedPayload = 0
        for (i <- 1 to 100) {
          if (dut.io.sampleOut.valid.toBoolean) {
            validAssertCount += 1
            if (cyclesToValid == 0) {
              cyclesToValid = i
              observedPayload = dut.io.sampleOut.payload.toInt
            }
          }
          
          // Next phaseTick at cycle 50 (49 cycles after waitSampling above)
          if (i == 49) {
            dut.io.phaseTick #= true
          } else {
            dut.io.phaseTick #= false
          }
          dut.clockDomain.waitSampling()
        }

        assert(validAssertCount == 1, s"sampleOut.valid was asserted $validAssertCount times instead of exactly once")
        assert(cyclesToValid == 50, s"Output flow valid asserted after $cyclesToValid cycles instead of 50")
        assert(observedPayload == expected, s"Expected payload $expected, got $observedPayload")
      }

      println("Verifying Attenuator Test Vectors:")
      checkScaling(20000, 255, 19921)
      checkScaling(20000, 128, 10000)
      checkScaling(-20000, 64, -5000)
      checkScaling(-32768, 0, 0)
      println("Test Vectors passed successfully.")

      // Pipelining throughput test (3 consecutive back-to-back samples on phaseTick grid)
      println("Verifying Pipelined Throughput (Back-to-Back phaseTick cycles):")
      
      // Cycle 0: Sample 10000, Vol 255
      dut.io.phaseTick #= true
      dut.io.sampleIn.valid #= true
      dut.io.sampleIn.payload #= 10000
      dut.io.volume #= 255
      sleep(1) // Combinational settling
      
      dut.clockDomain.waitSampling() // Sample 1 is latched. Output 1 is NOT ready yet.
      
      dut.io.phaseTick #= false
      dut.io.sampleIn.valid #= false
      dut.clockDomain.waitSampling(49) // Wait until next phaseTick cycle

      // Cycle 50 (phaseTick 2): Sample 20000, Vol 128
      dut.io.phaseTick #= true
      dut.io.sampleIn.valid #= true
      dut.io.sampleIn.payload #= 20000
      dut.io.volume #= 128
      sleep(1) // Combinational settling
      
      assert(dut.io.sampleOut.valid.toBoolean == true)
      assert(dut.io.sampleOut.payload.toInt == 9960) // 10000 * 255 / 256 = 9960
      
      dut.clockDomain.waitSampling()
      
      dut.io.phaseTick #= false
      dut.io.sampleIn.valid #= false
      dut.clockDomain.waitSampling(49)

      // Cycle 100 (phaseTick 3): Sample -10000, Vol 64
      dut.io.phaseTick #= true
      dut.io.sampleIn.valid #= true
      dut.io.sampleIn.payload #= -10000
      dut.io.volume #= 64
      sleep(1) // Combinational settling
      
      assert(dut.io.sampleOut.valid.toBoolean == true)
      assert(dut.io.sampleOut.payload.toInt == 10000) // 20000 * 128 / 256 = 10000
      
      dut.clockDomain.waitSampling()
      
      dut.io.phaseTick #= false
      dut.io.sampleIn.valid #= false
      dut.clockDomain.waitSampling(49)

      // Cycle 150 (phaseTick 4): Idle
      dut.io.phaseTick #= true
      dut.io.sampleIn.valid #= false
      sleep(1) // Combinational settling
      
      assert(dut.io.sampleOut.valid.toBoolean == true)
      assert(dut.io.sampleOut.payload.toInt == -2500) // -10000 * 64 / 256 = -2500
      
      dut.clockDomain.waitSampling()
      
      dut.io.phaseTick #= false
      dut.clockDomain.waitSampling(49)

      // Cycle 200 (phaseTick 5): Pipeline empty!
      dut.io.phaseTick #= true
      sleep(1) // Combinational settling
      assert(dut.io.sampleOut.valid.toBoolean == false)
      dut.clockDomain.waitSampling()

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
      dut.io.phaseTick #= false
      
      // Advance simulation time by 50 units (5 cycles) while reset is active
      sleep(50)
      
      // Assert outputs are strictly quiet/zeroed during reset
      assert(dut.io.sampleOut.payload.toInt == 0, "Output payload must be held at 0 during reset")
      assert(dut.io.sampleOut.valid.toBoolean == false, "Output valid must be held at False during reset")
      
      // 3. Clear inputs and wait for power-on reset to finish deasserting and stabilize
      dut.io.sampleIn.valid #= false
      dut.io.sampleIn.payload #= 0
      dut.io.volume #= 0
      dut.io.phaseTick #= false
      dut.clockDomain.waitSampling(100)
      println("10-bit Reset Stability verified successfully.")

      // Helper to check standard mathematical scaling
      def checkScaling(sample: Int, volume: Int, expected: Int): Unit = {
        // Start of period: Assert phaseTick and valid input sample
        dut.io.phaseTick #= true
        dut.io.sampleIn.valid #= true
        dut.io.sampleIn.payload #= sample
        dut.io.volume #= volume
        dut.clockDomain.waitSampling()

        dut.io.phaseTick #= false
        dut.io.sampleIn.valid #= false
        dut.io.sampleIn.payload #= 0

        var validAssertCount = 0
        var cyclesToValid = 0
        var observedPayload = 0
        for (i <- 1 to 100) {
          if (dut.io.sampleOut.valid.toBoolean) {
            validAssertCount += 1
            if (cyclesToValid == 0) {
              cyclesToValid = i
              observedPayload = dut.io.sampleOut.payload.toInt
            }
          }
          
          // Next phaseTick at cycle 50
          if (i == 49) {
            dut.io.phaseTick #= true
          } else {
            dut.io.phaseTick #= false
          }
          dut.clockDomain.waitSampling()
        }

        assert(validAssertCount == 1, s"sampleOut.valid was asserted $validAssertCount times instead of exactly once")
        assert(cyclesToValid == 50, s"Output flow valid asserted after $cyclesToValid cycles instead of 50")
        assert(observedPayload == expected, s"Expected payload $expected, got $observedPayload")
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
      
      // Cycle 0: Sample 10000, Vol 1023
      dut.io.phaseTick #= true
      dut.io.sampleIn.valid #= true
      dut.io.sampleIn.payload #= 10000
      dut.io.volume #= 1023
      sleep(1) // Combinational settling
      
      dut.clockDomain.waitSampling()
      
      dut.io.phaseTick #= false
      dut.io.sampleIn.valid #= false
      dut.clockDomain.waitSampling(49)

      // Cycle 50 (phaseTick 2): Sample 20000, Vol 512
      dut.io.phaseTick #= true
      dut.io.sampleIn.valid #= true
      dut.io.sampleIn.payload #= 20000
      dut.io.volume #= 512
      sleep(1) // Combinational settling
      
      assert(dut.io.sampleOut.valid.toBoolean == true)
      assert(dut.io.sampleOut.payload.toInt == 9990) // 10000 * 1023 / 1024 = 9990
      
      dut.clockDomain.waitSampling()
      
      dut.io.phaseTick #= false
      dut.io.sampleIn.valid #= false
      dut.clockDomain.waitSampling(49)

      // Cycle 100 (phaseTick 3): Sample -10000, Vol 256
      dut.io.phaseTick #= true
      dut.io.sampleIn.valid #= true
      dut.io.sampleIn.payload #= -10000
      dut.io.volume #= 256
      sleep(1) // Combinational settling
      
      assert(dut.io.sampleOut.valid.toBoolean == true)
      assert(dut.io.sampleOut.payload.toInt == 10000) // 20000 * 512 / 1024 = 10000
      
      dut.clockDomain.waitSampling()
      
      dut.io.phaseTick #= false
      dut.io.sampleIn.valid #= false
      dut.clockDomain.waitSampling(49)

      // Cycle 150 (phaseTick 4): Idle
      dut.io.phaseTick #= true
      dut.io.sampleIn.valid #= false
      sleep(1) // Combinational settling
      
      assert(dut.io.sampleOut.valid.toBoolean == true)
      assert(dut.io.sampleOut.payload.toInt == -2500) // -10000 * 256 / 1024 = -2500
      
      dut.clockDomain.waitSampling()
      
      dut.io.phaseTick #= false
      dut.clockDomain.waitSampling(49)

      // Cycle 200 (phaseTick 5): Pipeline empty!
      dut.io.phaseTick #= true
      sleep(1) // Combinational settling
      assert(dut.io.sampleOut.valid.toBoolean == false)
      dut.clockDomain.waitSampling()

      println("10-bit Pipelined Throughput verified successfully.")
    }
  }
}
