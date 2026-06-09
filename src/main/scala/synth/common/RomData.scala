package synth.common

object RomData {
  // --- Envelope Ctrl Config (Clock: 24 MHz, range: 0.5 ms to 30.0 s) ---
  val ClockFreq = 24000000.0
  val TMin = 0.0005
  val TMax = 30.0

  val envelopeRateLut: Seq[BigInt] = (0 until 256).map { p =>
    val t = TMin * scala.math.pow(TMax / TMin, p / 255.0)
    val inc = scala.math.round(scala.math.pow(2, 32) / (t * ClockFreq))
    BigInt(inc)
  }

  // --- Envelope Shaper Curves (0 to 256 entries) ---
  val linearCurveLut: Seq[BigInt] = (0 to 256).map { x =>
    BigInt(scala.math.min(255, x))
  }

  val expCurveLut: Seq[BigInt] = (0 to 256).map { x =>
    val factor = scala.math.min(255.0, x.toDouble) / 255.0
    val v = 255.0 * (scala.math.exp(3.0 * factor) - 1.0) / (scala.math.exp(3.0) - 1.0)
    BigInt(scala.math.round(v).toLong)
  }

  val logCurveLut: Seq[BigInt] = (0 to 256).map { x =>
    val factor = scala.math.min(255.0, x.toDouble) / 255.0
    val v = 255.0 * scala.math.log1p(7.0 * factor) / scala.math.log1p(7.0)
    BigInt(scala.math.round(v).toLong)
  }

  val sigCurveLut: Seq[BigInt] = (0 to 256).map { x =>
    val factor = scala.math.min(255.0, x.toDouble) / 255.0
    val v = 255.0 * (1.0 - scala.math.cos(scala.math.Pi * factor)) / 2.0
    BigInt(scala.math.round(v).toLong)
  }

  // --- Filter Cutoff and Resonance ---
  val filterCutoffLut: Seq[BigInt] = (0 until 256).map { p =>
    val coeffVal = scala.math.round(10.0 * scala.math.pow(4095.0 / 10.0, p / 255.0))
    BigInt(coeffVal)
  }

  val filterResonanceLut: Seq[BigInt] = (0 until 256).map { r =>
    val coeffVal = scala.math.round(255.0 - 251.0 * scala.math.pow(r / 255.0, 2.0))
    BigInt(coeffVal)
  }
}
