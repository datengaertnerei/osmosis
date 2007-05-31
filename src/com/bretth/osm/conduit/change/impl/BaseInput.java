package com.bretth.osm.conduit.change.impl;

import java.util.concurrent.locks.Condition;

import com.bretth.osm.conduit.ConduitRuntimeException;
import com.bretth.osm.conduit.data.OsmElement;


/**
 * Provides common functionality across input sources to a task processing
 * multiple inputs. It requires that all input sources provide data in a
 * specified order, specifically nodes, then segments, then ways all ordered by
 * their unique identifier.
 * 
 * @author Brett Henderson
 */
public abstract class BaseInput {
	/**
	 * Defines the various outcomes possible when performing a comparison of
	 * elements between data sources.
	 * 
	 * @author Brett Henderson
	 */
	protected enum ComparisonOutcome {
		/**
		 * The other source is at the same element as this source.
		 */
		SameElement,
		/**
		 * The current element does not exist in the other source.
		 */
		DifferentElement
	}


	/**
	 * Ensures that the latest data element received has been received in the
	 * correct order for processing.
	 * 
	 * @param oldStatus
	 *            The old processing status.
	 * @param newStatus
	 *            The new processing status. This may be the same as the old
	 *            status.
	 * @param oldElement
	 *            The previously received data element.
	 * @param newElement
	 *            The newly received data element.
	 */
	protected void validateState(InputStatus oldStatus,
			InputStatus newStatus, OsmElement oldElement, OsmElement newElement) {
		// Make sure we haven't gone past the processing stage for the new data
		// element.
		if (newStatus.compareTo(oldStatus) < 0) {
			throw new ConduitRuntimeException("Cannot perform " + newStatus
					+ " processing on element with id " + newElement.getId()
					+ " after " + oldStatus + " processing.");
		}

		// Make sure the data element hasn't been received out of order.
		if (oldElement != null && (oldElement.getId() >= newElement.getId())) {
			throw new ConduitRuntimeException("Received data element with id "
					+ newElement.getId() + " after element with id "
					+ oldElement.getId() + " during " + newStatus
					+ " processing.");
		}
	}


	/**
	 * <p>
	 * Performs a comparison of the data available on both sources.
	 * </p>
	 * <p>
	 * The following statuses may be returned.
	 * <ul>
	 * <li>ComparisonOutcome.DifferentElement - The other source doesn't
	 * contain this element.</li>
	 * <li>ComparisonOutcome.SameElement - The other source contains this
	 * element.</li>
	 * </ul>
	 * </p>
	 * <p>
	 * The blockOnSameElement must be set for one thread and not the other. If
	 * this is set, the calling thread will block until the other source
	 * progresses past this element. When not set, the other source will remain
	 * at this element until this source moves on.
	 * </p>
	 * 
	 * @param lockCondition
	 *            This condition is used to relinquish control of the main lock
	 *            until the other source performs an action requiring this
	 *            thread to wake up and continue.
	 * @param thisSourceStatus
	 *            The current status of this source.
	 * @param comparisonSourceStatus
	 *            The current status of the other source.
	 * @param thisSourceElement
	 *            The current element of this source.
	 * @param comparisonSourceElement
	 *            The current element of the other source.
	 * @param blockOnSameElement
	 *            If true, we will block if the other source is at the same
	 *            element, only one source should set this flag or deadlock will
	 *            occur.
	 * @return The comparison outcome.
	 */
	protected ComparisonOutcome performElementComparison(
			Condition lockCondition,
			InputStatus thisSourceStatus, InputStatus comparisonSourceStatus,
			OsmElement thisSourceElement, OsmElement comparisonSourceElement,
			boolean blockOnSameElement) {
		boolean sameElementDetected;
		
		// This will become true if the same element is detected on both
		// sources.
		sameElementDetected = false;
		
		// Loop until the other source reaches a point where we can continue.
		for (;;) {
			// Check if we're at a lesser element than the other source.
			if (
					(thisSourceStatus.compareTo(comparisonSourceStatus) < 0)
					||
					(
							(thisSourceStatus.equals(comparisonSourceStatus)) &&
							(thisSourceElement.getId() < comparisonSourceElement.getId())
					)
				) {
				// If the same element was reached on a previous iteration there
				// is no change, otherwise this is a changed element.
				if (sameElementDetected) {
					return ComparisonOutcome.SameElement;
				} else {
					return ComparisonOutcome.DifferentElement;
				}
			}
			
			// Check if we're at the same element as the other source.
			if (thisSourceStatus == comparisonSourceStatus && thisSourceElement.getId() == comparisonSourceElement.getId()) {
				// If we don't need to block on the same element we notify
				// instantly, otherwise we set the same element flag and
				// continue to the block point.
				if (!blockOnSameElement) {
					return ComparisonOutcome.SameElement;
				} else {
					sameElementDetected = true;
				}
			}
			
			// No decisions can be made until the other source progresses
			// further, we must wait.
			try {
				lockCondition.await();
			} catch (InterruptedException e) {
				throw new ConduitRuntimeException("Thread was interrupted.", e);
			}
		}
	}
}