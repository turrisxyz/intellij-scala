package org.jetbrains.plugins.scala.debugger.ui

import com.intellij.debugger.engine.SuspendContext
import com.intellij.debugger.engine.evaluation.{EvaluateException, EvaluationContext}
import com.intellij.debugger.engine.managerThread.SuspendContextCommand

import java.util.concurrent.CompletableFuture
import scala.jdk.FunctionConverters._
import scala.util.control.NonFatal

package object util {

  implicit class CompletableFutureOps[A](private val cf: CompletableFuture[A]) extends AnyVal {
    def flatMap[B](f: A => CompletableFuture[B]): CompletableFuture[B] =
      cf.thenComposeAsync(f.asJava)

    def map[B](f: A => B): CompletableFuture[B] =
      cf.thenApply(f.asJava)

    def attempt: CompletableFuture[Either[Throwable, A]] =
      cf.handle {
        case (a, null) => Right(a)
        case (_, t) => Left(t)
      }

    def rethrow[AA](implicit ev: A <:< Either[Throwable, AA]): CompletableFuture[AA] =
      cf.flatMap { a =>
        ev(a) match {
          case Left(t) => CompletableFuture.failedFuture(t)
          case Right(a) => CompletableFuture.completedFuture(a)
        }
      }

    def flatTap(f: A => CompletableFuture[Unit]): CompletableFuture[A] =
      cf.flatMap(a => f(a).map(_ => a))

    def flatten[AA](implicit ev: A <:< CompletableFuture[AA]): CompletableFuture[AA] =
      cf.flatMap(ev)
  }

  implicit class CompletableFutureTraverseOps[A](private val cfs: Seq[CompletableFuture[A]]) extends AnyVal {
    def sequence: CompletableFuture[Seq[A]] =
      cfs.foldLeft(CompletableFuture.completedFuture(Vector.empty[A])) { (acc, f) =>
        for {
          vec <- acc
          a <- f
        } yield vec :+ a
      }.map(_.toSeq)
  }

  def onDebuggerManagerThread[A](context: SuspendContext)(thunk: => A): CompletableFuture[A] = {
    val future = new CompletableFuture[A]()
    val thread = context.getDebugProcess.getManagerThread
    thread.invokeCommand(new SuspendContextCommand {
      override def getSuspendContext: SuspendContext = context

      override def action(): Unit = {
        try future.complete(thunk)
        catch {
          case NonFatal(t) => future.completeExceptionally(t)
        }
      }

      override def commandCancelled(): Unit = future.cancel(false)
    })
    future
  }

  def onDebuggerManagerThread[A](context: EvaluationContext)(thunk: => A): CompletableFuture[A] =
    onDebuggerManagerThread(context.getSuspendContext)(thunk)

  def evaluationExceptionFromThrowable(t: Throwable): EvaluateException = t match {
    case e: EvaluateException => e
    case _ => new EvaluateException(t.getMessage)
  }
}
