package scalaz.concurrent

import java.util.concurrent.{ScheduledExecutorService, ConcurrentLinkedQueue, ExecutorService}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}

import scalaz._
import scalaz.Tags.Parallel
import scalaz.syntax.monad._
import scalaz.std.list._
import scalaz.Free.Trampoline
import scalaz.\/._

import collection.JavaConversions._
import scala.concurrent.duration._

/**
 * `Task[A]` wraps a `scalaz.concurrent.Future[Throwable \/ A]`,
 * with some convenience functions for handling exceptions. Its
 * `Monad` and `Nondeterminism` instances are derived from [[Future]].
 *
 * `Task` (and `Future`) differ in several key ways from the `Future`
 * implementation in Scala 2.10 , and have a number of advantages. See the
 * documentation for [[scalaz.concurrent.Future]] for more information.
 *
 * `Task` is exception-safe when constructed using the primitives
 * in the companion object, but when calling the constructor, you
 * are responsible for ensuring the exception safety of the provided
 * `Future`.
 */
class Task[+A](val get: Future[Throwable \/ A]) {

  def flatMap[B](f: A => Task[B]): Task[B] =
    new Task(get flatMap {
      case -\/(e) => Future.now(-\/(e))
      case \/-(a) => Task.Try(f(a)) match {
        case e @ -\/(_) => Future.now(e)
        case \/-(task) => task.get
      }
    })

  def map[B](f: A => B): Task[B] =
    new Task(get map { _ flatMap {a => Task.Try(f(a))} })

  /** 'Catches' exceptions in the given task and returns them as values. */
  def attempt: Task[Throwable \/ A] =
    new Task(get map {
      case -\/(e) => \/-(-\/(e))
      case \/-(a) => \/-(\/-(a))
    })

  /**
   * Returns a new `Task` in which `f` is scheduled to be run on completion.
   * This would typically be used to release any resources acquired by this
   * `Task`.
   */
  def onFinish(f: Option[Throwable] => Task[Unit]): Task[A] =
    new Task(get flatMap {
      case -\/(e) => f(Some(e)).get *> Future.now(-\/(e))
      case r => f(None).get *> Future.now(r)
    })

  /**
   * Calls `attempt` and handles some exceptions using the given partial
   * function, calling Task.now on the result. Any nonmatching exceptions
   * are reraised.
   */
  def handle[B>:A](f: PartialFunction[Throwable,B]): Task[B] =
    handleWith(f andThen Task.now)

  /**
   * Calls `attempt` and handles some exceptions using the given partial
   * function. Any nonmatching exceptions are reraised.
   */
  def handleWith[B>:A](f: PartialFunction[Throwable,Task[B]]): Task[B] =
    attempt flatMap {
      case -\/(e) => f.lift(e) getOrElse Task.fail(e)
      case \/-(a) => Task.now(a)
    }

  /**
   * Runs this `Task`, and if it fails with an exception, runs `t2`.
   * This is rather coarse-grained. Use `attempt`, `handle`, and
   * `flatMap` for more fine grained control of exception handling.
   */
  def or[B>:A](t2: Task[B]): Task[B] =
    new Task(this.get flatMap {
      case -\/(e) => t2.get
      case a => Future.now(a)
    })

  /**
   * Run this `Task` and block until its result is available. This will
   * throw any exceptions generated by the `Task`. To return exceptions
   * in an `\/`, use `attemptRun`.
   */
  def unsafePerformSync: A = 
    get.unsafePerformSync match {
      case -\/(e) => throw e
      case \/-(a) => a
    }

  @deprecated("use unsafePerformSync", "7.2")
  def run: A =
    unsafePerformSync
  
  /** Like `run`, but returns exceptions as values. */
  def unsafePerformSyncAttempt: Throwable \/ A =
    try get.unsafePerformSync catch { case t: Throwable => -\/(t) }

  @deprecated("use unsafePerformSyncAttempt", "7.2")
  def attemptRun: Throwable \/ A =
    unsafePerformSyncAttempt
  
  /**
   * Run this computation to obtain an `A`, so long as `cancel` remains false.
   * Because of trampolining, we get frequent opportunities to cancel
   * while stepping through the trampoline, this should provide a fairly
   * robust means of cancellation.
   */
  def unsafePerformAsyncInterruptibly(f: (Throwable \/ A) => Unit, cancel: AtomicBoolean): Unit =
    get.unsafePerformAsyncInterruptibly(f, cancel)

  @deprecated("use unsafePerformAsyncInterruptibly", "7.2")
  def runAsyncInterruptibly(f: (Throwable \/ A) => Unit, cancel: AtomicBoolean): Unit =
    unsafePerformAsyncInterruptibly(f, cancel)
    
  /**
   * Similar to `unsafePerformAsyncInterruptibly(f,cancel)` except instead of interrupting by setting cancel to true,
   * It returns the function, that, when applied will interrupt the task.
   *
   * This allows "deterministic" completion of task computation
   * even if it was interrupted.
   * That means task will complete even when interrupted,
   * but with `TaskInterrupted` exception.
   *
   * Note 1: When Interrupted, the `f` callback will run in thread that called the `Interrupting` function () => Unit
   * Note 2: If task has handler like attempt, it won't get consulted for handling TaskInterrupted excpetion
   * @param f
   * @return
   */
  def unsafePerformAsyncInterruptibly(f: (Throwable \/ A) => Unit) : () => Unit = {
    val completed : AtomicBoolean = new AtomicBoolean(false)
    val a = Actor[Option[Throwable \/ A]] ({
      case Some(r) if ! completed.get =>
        completed.set(true)
        f(r)
      case None if ! completed.get  =>
        completed.set(true)
        f(left(Task.TaskInterrupted))
      case _ => () //already completed
    })(Strategy.Sequential)

    get.unsafePerformAsyncInterruptibly(r => a ! Some(r), completed)
    () => { a ! None }
  }

  @deprecated("use unsafePerformAsyncInterruptibly", "7.2")
  def runAsyncInterruptibly(f: (Throwable \/ A) => Unit) : () => Unit = 
    unsafePerformAsyncInterruptibly(f)
  
  /**
   * Run this computation to obtain either a result or an exception, then
   * invoke the given callback. Any pure, non-asynchronous computation at the
   * head of this `Task` will be forced in the calling thread. At the first
   * `Async` encountered, control to whatever thread backs the `Async` and
   * this function returns immediately.
   */
  def unsafePerformAsync(f: (Throwable \/ A) => Unit): Unit =
    get.unsafePerformAsync(f)

  @deprecated("use unsafePerformAsync", "7.2")
  def runAsync(f: (Throwable \/ A) => Unit): Unit =
    unsafePerformAsync(f)
    
  /**
   * Run this `Task` and block until its result is available, or until
   * `timeoutInMillis` milliseconds have elapsed, at which point a `TimeoutException`
   * will be thrown and the `Task` will attempt to be canceled.
   */
  def unsafePerformSyncFor(timeoutInMillis: Long): A = 
    get.unsafePerformSyncFor(timeoutInMillis) match {
      case -\/(e) => throw e
      case \/-(a) => a
    }

  def unsafePerformSyncFor(timeout: Duration): A = 
    unsafePerformSyncFor(timeout.toMillis)

  @deprecated("use unsafePerformSyncFor", "7.2")
  def runFor(timeoutInMillis: Long): A = 
    unsafePerformSyncFor(timeoutInMillis)

  @deprecated("use unsafePerformSyncFor", "7.2")
  def runFor(timeout: Duration): A = 
    unsafePerformSyncFor(timeout)

  /**
   * Like `unsafePerformSyncFor`, but returns exceptions as values. Both `TimeoutException`
   * and other exceptions will be folded into the same `Throwable`.
   */
  def unsafePerformSyncAttemptFor(timeoutInMillis: Long): Throwable \/ A =
    get.unsafePerformSyncAttemptFor(timeoutInMillis).join

  @deprecated("use unsafePerformSyncAttemptFor", "7.2")
  def attemptRunFor(timeoutInMillis: Long): Throwable \/ A =
    unsafePerformSyncAttemptFor(timeoutInMillis)

  def unsafePerformSyncAttemptFor(timeout: Duration): Throwable \/ A =
    unsafePerformSyncAttemptFor(timeout.toMillis)

  @deprecated("use unsafePerformSyncAttemptFor", "7.2")
  def attemptRunFor(timeout: Duration): Throwable \/ A =
    unsafePerformSyncAttemptFor(timeout)

  /**
   * A `Task` which returns a `TimeoutException` after `timeoutInMillis`,
   * and attempts to cancel the running computation.
   */
  def timed(timeoutInMillis: Long)(implicit scheduler:ScheduledExecutorService): Task[A] =
    new Task(get.timed(timeoutInMillis).map(_.join))

  def timed(timeout: Duration)(implicit scheduler:ScheduledExecutorService = Strategy.DefaultTimeoutScheduler): Task[A] =
    timed(timeout.toMillis)

  @deprecated("use unsafePerformTimed", "7.2")
  def unsafePerformTimed(timeout: Duration)(implicit scheduler:ScheduledExecutorService = Strategy.DefaultTimeoutScheduler): Task[A] =
    timed(timeout)
  
  @deprecated("use unsafePerformTimed", "7.2")
  def unsafePerformTimed(timeoutInMillis: Long)(implicit scheduler:ScheduledExecutorService): Task[A] =
    timed(timeoutInMillis)
 
  /**
   * Retries this task if it fails, once for each element in `delays`,
   * each retry delayed by the corresponding duration, accumulating
   * errors into a list.
   * A retriable failure is one for which the predicate `p` returns `true`.
   */
  def retryAccumulating(delays: Seq[Duration], p: (Throwable => Boolean) = _.isInstanceOf[Exception]): Task[(A, List[Throwable])] =
    retryInternal(delays, p, true)

  @deprecated("use unsafePerformRetryAccumulating", "7.2")
  def unsafePerformRetryAccumulating(delays: Seq[Duration], p: (Throwable => Boolean) = _.isInstanceOf[Exception]): Task[(A, List[Throwable])] =
    retryAccumulating(delays, p)
    
  /**
   * Retries this task if it fails, once for each element in `delays`,
   * each retry delayed by the corresponding duration.
   * A retriable failure is one for which the predicate `p` returns `true`.
   */
  def retry(delays: Seq[Duration], p: (Throwable => Boolean) = _.isInstanceOf[Exception]): Task[A] =
    retryInternal(delays, p, false).map(_._1)

  @deprecated("use unsafePerformRetry", "7.2")
  def unsafePerformRetry(delays: Seq[Duration], p: (Throwable => Boolean) = _.isInstanceOf[Exception]): Task[A] =
    retry(delays, p)

  private def retryInternal(delays: Seq[Duration],
                            p: (Throwable => Boolean),
                            accumulateErrors: Boolean): Task[(A, List[Throwable])] = {
      def help(ds: Seq[Duration], es: => Stream[Throwable]): Future[Throwable \/ (A, List[Throwable])] = {
        def acc = if (accumulateErrors) es.toList else Nil
          ds match {
            case Seq() => get map (_. map(_ -> acc))
            case Seq(t, ts @_*) => get flatMap {
              case -\/(e) if p(e) =>
                help(ts, e #:: es) after t
              case x => Future.now(x.map(_ -> acc))
            }
        }
      }
      Task.async { help(delays, Stream()).unsafePerformAsync }
    }

  /** Ensures that the result of this Task satisfies the given predicate, or fails with the given value. */
  def ensure(failure: => Throwable)(f: A => Boolean): Task[A] =
    flatMap(a => if(f(a)) Task.now(a) else Task.fail(failure))

  /**
   * Delays the execution of this `Task` by the duration `t`.
   */
  def after(t: Duration): Task[A] =
    new Task(get after t)
}

object Task {

  implicit val taskInstance: Nondeterminism[Task] with BindRec[Task] with Catchable[Task] with MonadError[Task,Throwable] =
    new Nondeterminism[Task] with BindRec[Task] with Catchable[Task] with MonadError[Task, Throwable] {
      val F = Nondeterminism[Future]
      def point[A](a: => A) = Task.point(a)
      def bind[A,B](a: Task[A])(f: A => Task[B]): Task[B] =
        a flatMap f
      def chooseAny[A](h: Task[A], t: Seq[Task[A]]): Task[(A, Seq[Task[A]])] =
        new Task ( F.map(F.chooseAny(h.get, t map (_ get))) { case (a, residuals) =>
          a.map((_, residuals.map(new Task(_))))
        })
      override def gatherUnordered[A](fs: Seq[Task[A]]): Task[List[A]] = {
        new Task (F.map(F.gatherUnordered(fs.map(_ get)))(eithers =>
          Traverse[List].sequenceU(eithers)
        ))
      }
      def fail[A](e: Throwable): Task[A] = new Task(Future.now(-\/(e)))
      def attempt[A](a: Task[A]): Task[Throwable \/ A] = a.attempt
      def tailrecM[A, B](f: A => Task[A \/ B])(a: A): Task[B] = Task.tailrecM(f)(a)
      def raiseError[A](e: Throwable): Task[A] = fail(e)
      def handleError[A](fa: Task[A])(f: Throwable => Task[A]): Task[A] =
        fa.handleWith { case t => f(t) }
    }

  /** signals task was interrupted **/
  case object TaskInterrupted extends InterruptedException {
    override def fillInStackTrace = this
  }

  def point[A](a: => A) = new Task(Future.delay(Try(a)))

  /** A `Task` which fails with the given `Throwable`. */
  def fail(e: Throwable): Task[Nothing] = new Task(Future.now(-\/(e)))

  /** Convert a strict value to a `Task`. Also see `delay`. */
  def now[A](a: A): Task[A] = new Task(Future.now(\/-(a)))

  /**
   * Promote a non-strict value to a `Task`, catching exceptions in
   * the process. Note that since `Task` is unmemoized, this will
   * recompute `a` each time it is sequenced into a larger computation.
   * Memoize `a` with a lazy value before calling this function if
   * memoization is desired.
   */
  def delay[A](a: => A): Task[A] = suspend(now(a))

  /**
   * Produce `f` in the main trampolining loop, `Future.step`, using a fresh
   * call stack. The standard trampolining primitive, useful for avoiding
   * stack overflows.
   */
  def suspend[A](a: => Task[A]): Task[A] = new Task(Future.suspend(
    Try(a.get) match {
      case -\/(e) => Future.now(-\/(e))
      case \/-(f) => f
  }))

  /** Create a `Task` that will evaluate `a` using the given `ExecutorService`. */
  def apply[A](a: => A)(implicit pool: ExecutorService = Strategy.DefaultExecutorService): Task[A] =
    new Task(Future(Try(a))(pool))

  /**
   * Create a `Task` that starts evaluating `a` using the given `ExecutorService` right away.
   * This will start executing side effects immediately, and is thus morally equivalent to
   * `unsafePerformIO`. The resulting `Task` cannot be rerun to repeat the effects.
   * Use with care.
   */
  def unsafeStart[A](a: => A)(implicit pool: ExecutorService = Strategy.DefaultExecutorService): Task[A] =
    new Task(Future(Task.Try(a))(pool).unsafeStart)

  /**
   * Returns a `Task` that produces the same result as the given `Future`,
   * but forks its evaluation off into a separate (logical) thread, using
   * the given `ExecutorService`. Note that this forking is only described
   * by the returned `Task`--nothing occurs until the `Task` is run.
   */
  def fork[A](a: => Task[A])(implicit pool: ExecutorService = Strategy.DefaultExecutorService): Task[A] =
    apply(a).join


  /**
   * Create a `Task` from an asynchronous computation, which takes the form
   * of a function with which we can register a callback. This can be used
   * to translate from a callback-based API to a straightforward monadic
   * version.
   */
  def async[A](register: ((Throwable \/ A) => Unit) => Unit): Task[A] =
    new Task(Future.async(register))

  def schedule[A](a: => A, delay: Duration)(implicit pool: ScheduledExecutorService =
    Strategy.DefaultTimeoutScheduler): Task[A] = new Task(Future.schedule(Try(a), delay))

  /**
   * Like `Nondeterminism[Task].gatherUnordered`, but if `exceptionCancels` is true,
   * exceptions in any task try to immediately cancel all other running tasks. If
   * `exceptionCancels` is false, in the event of an error, all tasks are run to completion
   * before the error is returned.
   * @since 7.0.3
   */
  def gatherUnordered[A](tasks: Seq[Task[A]], exceptionCancels: Boolean = false): Task[List[A]] =
    reduceUnordered[A, List[A]](tasks, exceptionCancels)

  def reduceUnordered[A, M](tasks: Seq[Task[A]], exceptionCancels: Boolean = false)(implicit R: Reducer[A, M]): Task[M] =
    if (!exceptionCancels) taskInstance.reduceUnordered(tasks)
    else tasks match {
      // Unfortunately we cannot reuse the future's combinator
      // due to early terminating requirement on task
      // when task fails.  This also makes implementation a bit trickier
      case Seq() => Task.now(R.zero)
      case Seq(t) => t.map(R.unit)
      case _ => new Task(Future.Async { cb =>
        val interrupt = new AtomicBoolean(false)
        val results = new ConcurrentLinkedQueue[M]
        val togo = new AtomicInteger(tasks.size)

        tasks.foreach { t =>
          val handle: (Throwable \/ A) => Trampoline[Unit] = {
            case \/-(success) =>
              // Try to reduce number of values in the queue
              val front = results.poll()
              if (front == null)
                results.add(R.unit(success))
              else
                results.add(R.cons(success, front))

              // only last completed f will hit the 0 here.
              if (togo.decrementAndGet() == 0)
                cb(\/-(results.toList.foldLeft(R.zero)((a, b) => R.append(a, b))))
              else
                Trampoline.done(())
            case e@(-\/(failure)) =>
              // Only allow the first failure to invoke the callback, so we
              // race to set `togo` to 0 here.
              // If we win, invoke the callback with our error, otherwise, noop
              @annotation.tailrec
              def firstFailure: Boolean = {
                val current = togo.get
                if (current > 0) {
                  if (togo.compareAndSet(current,0)) true
                  else firstFailure
                }
                else false
              }

              if (firstFailure) // invoke `cb`, then cancel any computation not running yet
                // food for thought - might be safe to set the interrupt first
                // but, this may also kill `cb(e)`
                // could have separate AtomicBooleans for each task
                cb(e) *> Trampoline.delay { interrupt.set(true); () }
              else
                Trampoline.done(())
          }
          t.get.unsafePerformListenInterruptibly(handle, interrupt)
        }
      })
    }

  /** Utility function - evaluate `a` and catch and return any exceptions. */
  def Try[A](a: => A): Throwable \/ A =
    try \/-(a) catch { case e: Throwable => -\/(e) }

  def fromMaybe[A](ma: Maybe[A])(t: => Throwable): Task[A] =
    ma.cata(Task.now, Task.fail(t))

  def fromDisjunction[A <: Throwable, B](x: A \/ B): Task[B] =
    x.fold(Task.fail, Task.now)

  def tailrecM[A, B](f: A => Task[A \/ B])(a: A): Task[B] =
    f(a).flatMap {
      case -\/(a0) => tailrecM(f)(a0)
      case \/-(b) => point(b)
    }

  /** type for Tasks which need to be executed in parallel when using an Applicative instance */
  type ParallelTask[A] = Task[A] @@ Parallel

  /** This Applicative instance runs Tasks in parallel.
   *
   * It is different from the Applicative instance obtained from Monad[Task] which runs tasks sequentially.
   */
  implicit val taskParallelApplicativeInstance: Applicative[ParallelTask] =
    taskInstance.parallel
}

