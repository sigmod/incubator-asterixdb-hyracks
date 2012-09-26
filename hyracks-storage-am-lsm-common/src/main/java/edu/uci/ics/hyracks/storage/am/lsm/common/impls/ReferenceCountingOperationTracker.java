package edu.uci.ics.hyracks.storage.am.lsm.common.impls;

import edu.uci.ics.hyracks.api.exceptions.HyracksDataException;
import edu.uci.ics.hyracks.storage.am.common.impls.NoOpOperationCallback;
import edu.uci.ics.hyracks.storage.am.lsm.common.api.ILSMIOOperationCallback;
import edu.uci.ics.hyracks.storage.am.lsm.common.api.ILSMIndex;
import edu.uci.ics.hyracks.storage.am.lsm.common.api.ILSMIndexAccessor;
import edu.uci.ics.hyracks.storage.am.lsm.common.api.ILSMOperationTracker;

public class ReferenceCountingOperationTracker implements ILSMOperationTracker {

    private int threadRefCount = 0;

    @Override
    public void threadEnter(ILSMIndex index) throws HyracksDataException {
        synchronized (this) {
            // flushFlag may be set to true even though the flush has not occurred yet.
            // If flushFlag is set, then the flush is queued to occur by the last exiting thread.
            // This operation should wait for that flush to occur before proceeding.
            if (!index.getFlushController().getFlushStatus(index)) {
                // Increment the threadRefCount in order to block the possibility of a concurrent flush.
                // The corresponding threadExit() call is in LSMTreeRangeSearchCursor.close()
            } else {
                try {
                    this.wait();
                } catch (InterruptedException e) {
                    throw new HyracksDataException(e);
                }
            }
            threadRefCount++;
        }
    }

    @Override
    public void threadExit(final ILSMIndex index) {
        synchronized (this) {
            threadRefCount--;

            // Flush will only be handled by last exiting thread.
            if (index.getFlushController().getFlushStatus(index) && threadRefCount == 0) {
                ILSMIndexAccessor accessor = (ILSMIndexAccessor) index.createAccessor(NoOpOperationCallback.INSTANCE,
                        NoOpOperationCallback.INSTANCE);
                index.getIOScheduler().scheduleOperation(accessor.createFlushOperation(new ILSMIOOperationCallback() {
                    @Override
                    public void callback() {
                        ReferenceCountingOperationTracker.this.notifyAll();
                    }
                }));
            }
        }
    }
}
