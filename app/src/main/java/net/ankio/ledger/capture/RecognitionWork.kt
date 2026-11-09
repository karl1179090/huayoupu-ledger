package net.ankio.ledger.capture

import kotlinx.coroutines.*
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/** Admission and completion ownership for every asynchronous recognition/maintenance writer. */
class RecognitionWork(private val scope:CoroutineScope) {
    private val jobs=linkedMapOf<Job,CompletableDeferred<Unit>>()
    @Volatile var accepting=true
        private set
    fun <T> admit(rejected:T,action:()->T):T=synchronized(this) { if(accepting)action() else rejected }
    fun launch(context:CoroutineContext=EmptyCoroutineContext,start:CoroutineStart=CoroutineStart.DEFAULT,
        onCompletion:()->Unit={},block:suspend CoroutineScope.()->Unit):Job {
        val job=synchronized(this) {
            if(!accepting)return Job().apply { cancel() }
            val finished=CompletableDeferred<Unit>()
            scope.launch(context,start=CoroutineStart.LAZY,block=block).also { job ->
                jobs[job]=finished
                job.invokeOnCompletion {
                    try { onCompletion() }
                    finally { synchronized(this) { jobs.remove(job) };finished.complete(Unit) }
                }
            }
        }
        if(start!=CoroutineStart.LAZY)job.start()
        return job
    }
    /** No application/store/task/media/queue monitor is held while waiting. */
    suspend fun closeAndDrain() {
        val pending=synchronized(this) { check(accepting);accepting=false;jobs.toMap() }
        pending.keys.forEach { it.cancel() };pending.values.awaitAll()
    }
    fun reopen()=synchronized(this) { check(jobs.isEmpty());accepting=true }
}
