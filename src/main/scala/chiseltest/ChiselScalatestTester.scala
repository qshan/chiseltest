// SPDX-License-Identifier: Apache-2.0

package chiseltest

import chiseltest.internal._
import chiseltest.experimental.{sanitizeFileName, ChiselTestShell}
import chisel3.Module
import chiseltest.internal.WriteVcdAnnotation
import firrtl.AnnotationSeq
import org.scalatest._
import org.scalatest.exceptions.TestFailedException

import scala.util.DynamicVariable

trait ChiselScalatestTester extends Assertions with TestSuiteMixin with TestEnvInterface { this: TestSuite =>

  class TestBuilder[T <: Module](
    val dutGen:        () => T,
    val annotationSeq: AnnotationSeq,
    val flags:         Array[String]) {
    def getTestName: String = {
      sanitizeFileName(scalaTestContext.value.get.name)
    }

    def apply(testFn: T => Unit): Unit = {
      val finalAnnos = addDefaultTargetDir(getTestName, (new ChiselTestShell).parse(flags) ++ annotationSeq) ++
        (if (scalaTestContext.value.get.configMap.contains("writeVcd")) {
           Seq(WriteVcdAnnotation)
         } else {
           Seq.empty
         })

      runTest(defaults.createDefaultTester(dutGen, finalAnnos))(testFn)
    }

    def run(testFn: T => Unit, annotations: AnnotationSeq): Unit = {
      runTest(defaults.createDefaultTester(dutGen, annotations))(testFn)
    }

    // TODO: in the future, allow reset and re-use of a compiled design to avoid recompilation cost per test
    val outer: ChiselScalatestTester = ChiselScalatestTester.this
  }

  // Provide test fixture data as part of 'global' context during test runs
  protected var scalaTestContext = new DynamicVariable[Option[NoArgTest]](None)

  abstract override def withFixture(test: NoArgTest): Outcome = {
    require(scalaTestContext.value.isEmpty)
    scalaTestContext.withValue(Some(test)) {
      super.withFixture(test)
    }
  }

  // Stack trace data to help generate more informative (and localizable) failure messages
  var topFileName: Option[String] = None // best guess at the testdriver top filename

  private def runTest[T <: Module](tester: BackendInstance[T])(testFn: T => Unit) {
    // Try and get the user's top-level test filename
    val internalFiles = Set("ChiselScalatestTester.scala", "BackendInterface.scala", "TestEnvInterface.scala")
    val topFileNameGuess = (new Throwable).getStackTrace.apply(2).getFileName
    if (internalFiles.contains(topFileNameGuess)) {
      println("Unable to guess top-level testdriver filename from stack trace")
      topFileName = None
    } else {
      topFileName = Some(topFileNameGuess)
    }

    batchedFailures.clear()

    try {
      Context.run(tester, this, testFn)
    } catch {
      // Translate testers2's FailedExpectException into ScalaTest TestFailedException that is more readable
      case exc: FailedExpectException =>
        val newExc = new TestFailedException(exc, exc.failedCodeStackDepth)
        newExc.setStackTrace(exc.getStackTrace)
        throw newExc
    }
  }

  /**
    * Constructs a unit test harness for the Chisel Module generated by dutGen.
    * General use looks like
    * {{{
    *   test(new PlusOne) { c =>
    *     // body of the unit test, c is a a reference
    *     c.io.input.poke(1.U)
    *     c.io.output.expect(2.U)
    *   }
    * }}}
    *
    * If you need to add options to this unit test you can tack on .withAnnotations modifier
    * or a .withFlags modifier. These modifiers can be used together.
    * You must add `import chisel3.tester.experimental.TestOptionBuilder._` to use .withAnnotations
    *
    * For example:
    * {{{
    *   test(new TestModule).withAnnotations(Seq(WriteVcdAnnotation)) { c =>
    *     // body of the unit test
    *   }
    * }}}
    * @see src/test/scala/chisel3/tests/OptionsBackwardCompatibilityTest for examples
    *
    * @note This API is experimental and forward compatibility is not yet guaranteed
    *
    * @param dutGen             A generator of a Chisel  Module
    * @tparam T                 The DUT type, must be a subclass of Module
    * @return
    */
  def test[T <: Module](dutGen: => T): TestBuilder[T] = {
    new TestBuilder(() => dutGen, Seq.empty, Array.empty)
  }
}
