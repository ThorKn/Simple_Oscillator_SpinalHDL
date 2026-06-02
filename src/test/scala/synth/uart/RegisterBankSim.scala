package synth.uart

import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

class RegisterBankSim extends AnyFunSuite {
  test("RegisterBank parameters update and atomic frequency updates") {
    SimConfig.withWave.compile(new RegisterBank).doSim { dut =>
      // 1. Fork the clock and reset using standard forkStimulus
      dut.clockDomain.forkStimulus(period = 10)

      // Helper to perform a clean register write transaction
      def writeReg(address: Int, data: Int): Unit = {
        dut.io.regWrite.valid #= true
        dut.io.regWrite.payload.address #= address
        dut.io.regWrite.payload.data #= data
        dut.clockDomain.waitSampling() // Cycle 1: Latch input into storage register
        dut.io.regWrite.valid #= false
        dut.clockDomain.waitSampling() // Cycle 2: Propagate to output synchronization register
        dut.clockDomain.waitSampling() // Cycle 3: Stable configuration visible on outputs
      }

      // 1.2.1 Verify Reset Defaults during active reset
      println("Verifying Reset Defaults:")
      dut.io.regWrite.valid #= true
      dut.io.regWrite.payload.address #= 0x03
      dut.io.regWrite.payload.data #= 0xAA
      
      // Advance simulation time by 50 units (5 cycles) while reset is active
      sleep(50)
      
      assert(dut.io.config.freqWord.toLong == 0, "Frequency must remain 0 during reset")
      assert(dut.io.config.waveSelect.toInt == 0, "waveSelect must remain 0 during reset")
      assert(dut.io.config.pwmWidth.toInt == 0, "pwmWidth must remain 0 during reset")
      assert(dut.io.config.volume.toInt == 0, "Volume must remain 0 during reset")
      
      // Assert Envelope config fields remain strictly 0 under active reset
      assert(dut.io.envConfig.ctrl.toInt == 0, "envConfig.ctrl must remain 0 during reset")
      assert(dut.io.envConfig.attack.toInt == 0, "envConfig.attack must remain 0 during reset")
      assert(dut.io.envConfig.decay.toInt == 0, "envConfig.decay must remain 0 during reset")
      assert(dut.io.envConfig.sustain.toInt == 0, "envConfig.sustain must remain 0 during reset")
      assert(dut.io.envConfig.release.toInt == 0, "envConfig.release must remain 0 during reset")
      assert(dut.io.envConfig.gate.toInt == 0, "envConfig.gate must remain 0 during reset")
      
      // Clear write signals and wait for reset deassertion and stabilization
      dut.io.regWrite.valid #= false
      dut.io.regWrite.payload.address #= 0
      dut.io.regWrite.payload.data #= 0
      dut.clockDomain.waitSampling(100)
      println("Reset Defaults verified successfully.")

      // 1.2.2 Verify Single-Byte Direct Updates
      println("Verifying Single-Byte Direct Updates:")
      
      // Write Waveform Select (0x03) -> 3 (Triangle)
      writeReg(0x03, 3)
      assert(dut.io.config.waveSelect.toInt == 3, s"Expected waveSelect to be 3, got ${dut.io.config.waveSelect.toInt}")

      // Write PWM Width (0x04) -> 0xA5
      writeReg(0x04, 0xA5)
      assert(dut.io.config.pwmWidth.toInt == 0xA5, s"Expected pwmWidth to be 0xA5, got ${dut.io.config.pwmWidth.toInt}")

      // Write Volume (0x05) -> 0x7F
      writeReg(0x05, 0x7F)
      assert(dut.io.config.volume.toInt == 0x7F, s"Expected volume to be 0x7F, got ${dut.io.config.volume.toInt}")

      println("Single-Byte Direct Updates verified successfully.")

      // 1.2.3 Verify Atomic 24-Bit Frequency Commitment
      println("Verifying Atomic Frequency Updates:")

      // Step 1: Write Low byte (FREQ_LOW) -> 0x55
      writeReg(0x00, 0x55)
      // Active frequency must remain unchanged (0)
      assert(dut.io.config.freqWord.toLong == 0, s"Expected frequency to remain 0 after FREQ_LOW write, got ${dut.io.config.freqWord.toLong}")

      // Step 2: Write Mid byte (FREQ_MID) -> 0xAA
      writeReg(0x01, 0xAA)
      // Active frequency must still remain unchanged (0)
      assert(dut.io.config.freqWord.toLong == 0, s"Expected frequency to remain 0 after FREQ_MID write, got ${dut.io.config.freqWord.toLong}")

      // Step 3: Write High byte (FREQ_HIGH) -> 0x0C (Trigger Commit)
      writeReg(0x02, 0x0C)
      // In the next cycle, the 24-bit frequency word must atomically update to 0x0CAA55 (830037)
      val expectedFreq = 0x0CAA55
      assert(dut.io.config.freqWord.toLong == expectedFreq, s"Expected atomic update to $expectedFreq, got ${dut.io.config.freqWord.toLong}")

      println("Atomic Frequency Updates verified successfully.")

      // 1.2.4 Verify Envelope Parameter Updates
      println("Verifying Envelope Parameter Updates:")
      writeReg(0x40, 0x15)
      assert(dut.io.envConfig.ctrl.toInt == 0x15, s"Expected envConfig.ctrl to be 0x15, got 0x${dut.io.envConfig.ctrl.toInt.toHexString}")

      writeReg(0x41, 0x0A)
      assert(dut.io.envConfig.attack.toInt == 0x0A, s"Expected envConfig.attack to be 0x0A, got ${dut.io.envConfig.attack.toInt}")

      writeReg(0x42, 0x1F)
      assert(dut.io.envConfig.decay.toInt == 0x1F, s"Expected envConfig.decay to be 0x1F, got ${dut.io.envConfig.decay.toInt}")

      writeReg(0x43, 0x80)
      assert(dut.io.envConfig.sustain.toInt == 0x80, s"Expected envConfig.sustain to be 0x80, got ${dut.io.envConfig.sustain.toInt}")

      writeReg(0x44, 0x2C)
      assert(dut.io.envConfig.release.toInt == 0x2C, s"Expected envConfig.release to be 0x2C, got ${dut.io.envConfig.release.toInt}")

      writeReg(0x45, 0x05)
      assert(dut.io.envConfig.gate.toInt == 0x05, s"Expected envConfig.gate to be 0x05, got 0x${dut.io.envConfig.gate.toInt.toHexString}")

      println("Envelope Parameter Updates verified successfully.")

      // 1.2.5 Verify Address Crosstalk & Channel Isolation
      println("Verifying Address Crosstalk & Channel Isolation:")
      
      // Step A: Capture current envelope values
      val oldCtrl        = dut.io.envConfig.ctrl.toInt
      val oldAttack      = dut.io.envConfig.attack.toInt
      val oldDecay       = dut.io.envConfig.decay.toInt
      val oldSustain     = dut.io.envConfig.sustain.toInt
      val oldRelease     = dut.io.envConfig.release.toInt
      val oldGate        = dut.io.envConfig.gate.toInt

      // Write to oscillator registers
      writeReg(0x03, 1) // Change waveSelect to 1
      writeReg(0x04, 0x50)
      writeReg(0x05, 0x30)

      // Verify envelope values are completely unaffected
      assert(dut.io.envConfig.ctrl.toInt == oldCtrl, "Envelope ctrl must be isolated from osc writes")
      assert(dut.io.envConfig.attack.toInt == oldAttack, "Envelope attack must be isolated from osc writes")
      assert(dut.io.envConfig.decay.toInt == oldDecay, "Envelope decay must be isolated from osc writes")
      assert(dut.io.envConfig.sustain.toInt == oldSustain, "Envelope sustain must be isolated from osc writes")
      assert(dut.io.envConfig.release.toInt == oldRelease, "Envelope release must be isolated from osc writes")
      assert(dut.io.envConfig.gate.toInt == oldGate, "Envelope gate must be isolated from osc writes")

      // Step B: Capture current oscillator values
      val oldFreq = dut.io.config.freqWord.toLong
      val oldWave = dut.io.config.waveSelect.toInt
      val oldPwm  = dut.io.config.pwmWidth.toInt
      val oldVol  = dut.io.config.volume.toInt

      // Write to envelope registers
      writeReg(0x40, 0x00)
      writeReg(0x41, 0x00)
      writeReg(0x42, 0x00)
      
      // Verify oscillator values are completely unaffected
      assert(dut.io.config.freqWord.toLong == oldFreq, "Oscillator frequency must be isolated from env writes")
      assert(dut.io.config.waveSelect.toInt == oldWave, "Oscillator waveSelect must be isolated from env writes")
      assert(dut.io.config.pwmWidth.toInt == oldPwm, "Oscillator pwmWidth must be isolated from env writes")
      assert(dut.io.config.volume.toInt == oldVol, "Oscillator volume must be isolated from env writes")

      println("Address Crosstalk & Channel Isolation verified successfully.")
    }
  }
}
