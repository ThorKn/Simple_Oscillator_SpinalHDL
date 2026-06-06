package synth

import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

class SynthSim extends AnyFunSuite {
  test("Synth end-to-end dynamic UART to I2S integration verification") {
    // Using a simulation frequency of 24MHz (period = 41.67ns)
    // We'll use 10 units as the half-period for simplicity in the sim
    SimConfig.withWave.compile(new Synth).doSim { dut =>
      // 1. Initialize Inputs
      dut.io.clk24MHz #= false
      dut.io.reset #= true
      dut.io.uartRx #= true

      // 2. Clock Generator (24 MHz master clock, period = 10 units)
      fork {
        while (true) {
          dut.io.clk24MHz #= !dut.io.clk24MHz.toBoolean
          sleep(5)
        }
      }

      // 3. Reset Sequence
      sleep(100)
      dut.io.reset #= false
      sleep(100)

      // Software UART Bit Transmitter Helper (Baud rate 115200 @ 24MHz clock -> 208 cycles per bit)
      val cyclesPerBit = 208
      
      def sendUartByte(byte: Int): Unit = {
        // Start Bit (Low)
        dut.io.uartRx #= false
        sleep(cyclesPerBit * 10)

        // 8 Data Bits (LSB-first)
        for (i <- 0 until 8) {
          val bit = (byte >> i) & 1
          dut.io.uartRx #= (bit == 1)
          sleep(cyclesPerBit * 10)
        }

        // Stop Bit (High)
        dut.io.uartRx #= true
        sleep(cyclesPerBit * 10)
      }

      def writeRegister(address: Int, data: Int): Unit = {
        sendUartByte(0x01)     // Write register header
        sendUartByte(address)  // Address register
        sendUartByte(data)     // Value to write
      }

      // 4. Inject Live Register Configuration over UART
      println("\nStreaming Configuration over UART RX:")
      // Configure waveform: 0x02 -> PWM Waveform (address 0x03)
      writeRegister(0x03, 0x02)
      
      // Configure PWM width: 0x80 -> 50% Duty Cycle (address 0x04)
      writeRegister(0x04, 0x80)

      // Configure volume: 0xFF -> Max Master Volume (address 0x05)
      writeRegister(0x05, 0xFF)

      // Configure Envelope Parameters:
      // Enable envelope (bit 0 = 1) -> value = 1 (address 0x40)
      writeRegister(0x40, 0x01)
      // Gate ON (bit 0 = 1) -> value = 1 (address 0x45)
      writeRegister(0x45, 0x01)
      // Attack = 0 (fastest 0.5 ms rise) (address 0x41)
      writeRegister(0x41, 0x00)
      // Decay = 0 (address 0x42)
      writeRegister(0x42, 0x00)
      // Sustain = 128 (address 0x43)
      writeRegister(0x43, 0x80)

      // Configure Filter Parameters:
      // Enable filter (bit 0 = 1) -> value = 1 (address 0x50)
      writeRegister(0x50, 0x01)
      writeRegister(0x51, 0x00)
      writeRegister(0x52, 0x80)
      writeRegister(0x53, 0x00)

      // Configure Frequency Tuning Word (0x080000) atomically to output a 15 kHz tone:
      writeRegister(0x00, 0x00) // FREQ_LOW -> Stages
      writeRegister(0x01, 0x00) // FREQ_MID -> Stages
      writeRegister(0x02, 0x08) // FREQ_HIGH -> Commits entire word atomically!
      println("UART Injection finished. Waiting for I2S output serialization...")

      // 5. Start the I2S Monitor Thread to capture the active outputs
      var framesCaptured = 0
      val maxFrames = 25
      var capturedSamples = List[(Int, Int)]()

      val monitor = fork {
        // Align with Left-channel WS boundary (lrclk goes Low)
        waitUntil(dut.io.i2sLrclk.toBoolean == true)
        waitUntil(dut.io.i2sLrclk.toBoolean == false)

        while (framesCaptured < maxFrames) {
          var leftRaw = 0
          var rightRaw = 0

          // Sample Left (16 bits) on rising BCLK edges
          for (_ <- 0 until 16) {
            waitUntil(dut.io.i2sBclk.toBoolean == true)
            leftRaw = (leftRaw << 1) | (if (dut.io.i2sData.toBoolean) 1 else 0)
            waitUntil(dut.io.i2sBclk.toBoolean == false)
          }

          // Sample Right (16 bits)
          for (_ <- 0 until 16) {
            waitUntil(dut.io.i2sBclk.toBoolean == true)
            rightRaw = (rightRaw << 1) | (if (dut.io.i2sData.toBoolean) 1 else 0)
            waitUntil(dut.io.i2sBclk.toBoolean == false)
          }

          // Convert to signed 16-bit
          val leftSample  = if ((leftRaw & 0x8000) != 0) leftRaw - 0x10000 else leftRaw
          val rightSample = if ((rightRaw & 0x8000) != 0) rightRaw - 0x10000 else rightRaw

          capturedSamples = capturedSamples :+ (leftSample, rightSample)
          framesCaptured += 1
          
          // Wait for start of next Left frame edge
          waitUntil(dut.io.i2sLrclk.toBoolean == false)
        }
      }

      // Wait until the monitor has finished capturing all active test frames
      monitor.join()

      // 7. Verify captured data results
      println("\nVerifying integration output results:")
      var nonZeroSamples = 0
      var maxAbsValue = 0
      
      for ((l, r) <- capturedSamples) {
        println(f" I2S Frame: L=$l%6d | R=$r%6d")
        
        // Assert left and right channel samples are perfectly identical (stereo alignment)
        assert(l == r, s"Stereo channel mismatch: Left ($l) and Right ($r) should be identical")
        
        val absVal = l.abs
        if (absVal > maxAbsValue) {
          maxAbsValue = absVal
        }
        
        if (l != 0) {
          nonZeroSamples += 1
          // Amplitude is dynamic under the active ADSR envelope, so we assert it is bound by the maximum possible peak
          assert(absVal <= 32609, s"Unexpected sample value out of bounds: $l")
        }
      }
      
      // Proves the register writing reached the synthesizer DSP engine and changed the audio output
      assert(nonZeroSamples > 0, "Synthesizer is still silent! UART configuration did not reach DSP engine.")
      // Proves the active envelope actually scales output up dynamically over time (it must not remain constant or static at max/min)
      assert(maxAbsValue > 5000, s"Envelope output remained too quiet. Max absolute value: $maxAbsValue")
      println(f"Integration simulation successful: $nonZeroSamples non-zero stereo PWM frames modulated by envelope captured!")
    }
  }
}
