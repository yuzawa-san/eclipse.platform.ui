/*******************************************************************************
 * Copyright (c) 2000, 2003 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials 
 * are made available under the terms of the Common Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/cpl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.text.edits;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

import org.eclipse.jface.text.Assert;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.Region;

/**
 * A text edit describes an elementary text manipulation operation. Edits are
 * executed by applying them to a document (e.g. an instance of <code>IDocument
 * </code>). 
 * <p>
 * Text edits form a tree. Clients can navigate the tree upwards, from child to 
 * parent, as well as downwards. Newly created edits are unparented. New edits
 * are added to the tree by calling one of the <code>add</code> methods on a parent
 * edit.
 * </p>
 * <p>
 * An edit tree is well formed in the following sense:
 * <ul>
 *   <li>a parent edit covers all its children</li>
 *   <li>children don't overlap</li>
 *   <li>an edit with length 0 can't have any children</li>
 * </ul>
 * Any manipulation of the tree that violates one of the above requirements results
 * in a <code>MalformedTreeException</code>.
 * </p>
 * <p>
 * Insert edits are represented by an edit of length 0. If more than one insert 
 * edit exists at the same offset then the edits are executed in the order in which
 * they have been added to a parent. The following code example:
 * <pre>
 *    IDocument document= new Document("org");
 * 	  MultiEdit edit= new MultiEdit();
 *    edit.add(new InsertEdit(0, "www.");
 *    edit.add(new InsertEdit(0, "eclipse.");
 *    edit.apply(document);
 * </pre> 
 * therefore results in string: "www.eclipse.org".
 * </p>
 * <p>
 * Text edits can be executed in a mode where the edit's region is updated to 
 * reflect the edit's position in the changed document. Region updating is enabled
 * by default or can be requested by passing <code>UPDATE_REGIONS</code> to the 
 * {@link #apply(IDocument, int) apply(IDocument, int)} method. In the above example
 * the region of the <code>InsertEdit(0, "eclipse.")</code> edit after executing
 * the root edit is <code>[3, 8]</code>. If the region of an edit got deleted during
 * change execution the region is set to <code>[-1, -1]</code> and the method {@link 
 * #isDeleted() isDeleted} returns <code>true</code>. 
 * </p>
 * This class isn't intended to be subclassed outside of the edit framework. Clients 
 * are only allowed to subclass <code>MultiTextEdit</code>.
 * 
 * @see TextBufferEditor
 * 
 * @since 3.0
 */
public abstract class TextEdit {

	/**
	 * Flags indicating that either <code>CREATE_UNDO</code> nor
	 * <code>UPDATE_REGIONS</code> is set. 
	 */
	public static final int NONE= 0;
	
	/**
	 * Flags indicating that applying an edit tree to a document
	 * is supposed to create a corresponding undo edit. If not
	 * specified <code>null</code> is returned from method <code>
	 * apply</code>.
	 */
	public static final int CREATE_UNDO= 1 << 0;
	
	/**
	 * Flag indicating that the edit's region will be updated to
	 * reflect its position in the changed document. If not specified
	 * when applying an edit tree to a document the edit's region will
	 * be arbitrary. It is even not guaranteed that the tree is still 
	 * well formed.
	 */
	public static final int UPDATE_REGIONS= 1 << 1;
	
	private static class InsertionComparator implements Comparator {
		public int compare(Object o1, Object o2) {
			TextEdit edit1 = (TextEdit) o1;
			TextEdit edit2 = (TextEdit) o2;
					
			int offset1 = edit1.getOffset();
			int length1 = edit1.getLength();
					
			int offset2 = edit2.getOffset();
			int length2 = edit2.getLength();
					
			// make sure that a duplicate insertion point at the same offet is 
			// inserted last. Have to double check with the spec. It says the
			// with identical values there is no guarantee which one will be 
			// found.
			if (offset1 == offset2 && length1 == 0 && length2 == 0) {
				return -1;
			}
			if (offset1 + length1 - 1 < offset2) {
				return -1;
			}
			if (offset2 + length2 - 1 < offset1) {
				return 1;
			}
			throw new MalformedTreeException(
					null, edit1, 
					TextEditMessages.getString("TextEdit.overlapping")); //$NON-NLS-1$
		}
	}
	
	private static final TextEdit[] EMPTY_ARRAY= new TextEdit[0];
	private static final InsertionComparator INSERTION_COMPARATOR= new InsertionComparator();
	
	private static final int DELETED_VALUE= -1;
	
	private int fOffset;
	private int fLength;
	
	/* package */ TextEdit fParent;
	/* package */ List fChildren;
	
	/* package */ int fDelta;

	/**
	 * Create a new text edit. Parent is initialized to <code>
	 * null<code> and the edit doesn't have any children.
	 */
	protected TextEdit(int offset, int length) {
		Assert.isTrue(offset >= 0 && length >= 0);
		fOffset= offset;
		fLength= length;
		fDelta= 0;
	}
	
	/**
	 * Copy constrcutor
	 * 
	 * @param source the source to copy form
	 */
	protected TextEdit(TextEdit source) {
		fOffset= source.fOffset;
		fLength= source.fLength;
		fDelta= 0;
	}

	//---- Region management -----------------------------------------------

	/**
	 * Returns the range that this edit is manipulating. The returned
	 * <code>IRegion</code> contains the edit's offset and length at
	 * the point in time when this call is made. Any subsequent changes
	 * to the edit's offset and length aren't reflected in the returned
	 * region object.
	 * <p>
	 * Creating a region for a deleted edit will result in an assertion
	 * failure.
	 * 
	 * @return the manipulated region
	 */
	public final IRegion getRegion() {
		return new Region(fOffset, fLength);
	}
	
	/**
	 * Returns the offset of the edit. An offset is a 0-based 
	 * character index. Returns <code>-1</code> if the edit
	 * is marked as deleted
	 * 
	 * @return the offset of the edit
	 */
	public final int getOffset() {
		return fOffset;
	}
	
	/**
	 * Returns the length of the edit. Returns <code>-1</code>
	 * if the edit is marked as deleted.
	 * 
	 * @return the length of the edit
	 */
	public final int getLength() {
		return fLength;
	}
	
	/**
	 * Returns the inclusive end position of this edit. The inclusive end
	 * position denotes the last character of the region manipulated by
	 * this edit. The returned value is the result of the following
	 * calculation:
	 * <pre>
	 *   getOffset() + getLength() - 1;
	 * <pre>
	 * 
	 * @return the inclusive end position
	 */
	public final int getInclusiveEnd() {
		return fOffset + fLength - 1;
	}
	
	/**
	 * Returns the exclusive end position of this edit. The exclusive end
	 * position denotes the next character of the region manipulated by 
	 * this edit. The returned value is the result of the following
	 * calculation:
	 * <pre>
	 *   getOffset() + getLength();
	 * </pre>
	 * 
	 * @return the exclusive end position
	 */
	public final int getExclusiveEnd() {
		return fOffset + fLength;
	}
	
	/**
	 * Returns whether this edit has been deleted or not.
	 * 
	 * @return <code>true</code> if the edit has been 
	 *  deleted; otherwise <code>false</code> is returned.
	 */
	public final boolean isDeleted() {
		return fOffset == DELETED_VALUE && fLength == DELETED_VALUE;
	}
	
	/**
	 * Returns <code>true</code> if the edit covers the given edit
	 * <code>other</code>. If the length of the edit is zero <code>
	 * false</code> is returned. An insert edit can't cover any other
	 * edit, even if the other edit has the same offset and length.
	 * 
	 * @param other the other edit
	 * @return <code>true<code> if the edit covers the other edit;
	 *  otherwise <code>false</code> is returned.
	 * @see Regions#covers(IRegion, IRegion)  
	 */
	public final boolean covers(TextEdit other) {
		if (fLength == 0) {	// an insert edit can't cover anything
			return false;
		} else {
			int otherOffset= other.fOffset;
			return fOffset <= otherOffset && otherOffset + other.fLength <= fOffset + fLength;
		}		
	}

	//---- parent and children management -----------------------------
	
	/**
	 * Returns the edit's parent. The method returns <code>null</code> 
	 * if this edit hasn't been add to another edit.
	 * 
	 * @return the edit's parent
	 */
	public final TextEdit getParent() {
		return fParent;
	}
	
	/**
	 * Adds the given edit <code>child</code> to this edit.
	 * 
	 * @param child the child edit to add
	 * @exception <code>MalformedTreeException<code> is thrown if the child
	 *  edit can't be added to this edit. This is the case if the child 
	 *  overlaps with one of its siblings or if the child edit's region
	 *  isn't fully covered by this edit.
	 */
	public final void addChild(TextEdit child) throws MalformedTreeException {
		internalAdd(child);
	}
	
	/**
	 * Adds all edits in <code>edits</code> to this edit.
	 * 
	 * @param edits the text edits to add
	 * @exception <code>MalformedTreeException</code> is thrown if one of 
	 *  the given edits can't be added to this edit.
	 * 
	 * @see #addChild(TextEdit)
	 */
	public final void addChildren(TextEdit[] edits) throws MalformedTreeException {
		for (int i= 0; i < edits.length; i++) {
			internalAdd(edits[i]);
		}
	}
	
	/**
	 * Removes the edit specified by the given index from the list
	 * of children. Returns the child edit that was removed from
	 * the list of children. The parent of the returned edit is
	 * set to <code>null</code>.
	 * 
	 * @param index the index of the edit to remove
	 * @return the removed edit
	 * @exception <code>IndexOutOfBoundsException</code> if the index 
	 *  is out of range
	 */
	public final TextEdit removeChild(int index) {
		if (fChildren == null)
			throw new IndexOutOfBoundsException("Index: " + index + " Size: 0");  //$NON-NLS-1$//$NON-NLS-2$
		TextEdit result= (TextEdit)fChildren.remove(index);
		result.internalSetParent(null);
		if (fChildren.isEmpty())
			fChildren= null;
		return result;
	}
	
	/**
	 * Removes the first occurrence of the given child from the list 
	 * of children.
	 * 
	 * @param child the child to be removed
	 * @return <code>true</code> if the edit contained the given
	 *  child; otherwise <code>false</code> is returned
	 */
	public final boolean removeChild(TextEdit child) {
		Assert.isNotNull(child);
		if (fChildren == null)
			return false;
		boolean result= fChildren.remove(child);
		if (result) {
			child.internalSetParent(null);
			if (fChildren.isEmpty())
				fChildren= null;
		}
		return result;
	}
	
	/**
	 * Removes all child edits from and returns them. The parent 
	 * of the removed edits is set to <code>null</code>.
	 * 
	 * @return an array of the removed edits
	 */
	public final TextEdit[] removeChildren() {
		if (fChildren == null)
			return EMPTY_ARRAY;
		int size= fChildren.size();
		TextEdit[] result= new TextEdit[size];
		for (int i= 0; i < size; i++) {
			result[i]= (TextEdit)fChildren.get(i);
			result[i].internalSetParent(null);
		}
		fChildren= null;
		return result;
	}
	
	/**
	 * Returns <code>true</code> if this edit has children. Otherwise
	 * <code>false</code> is returned.
	 * 
	 * @return <code>true</code> if this edit has children; otherwise
	 *  <code>false</code> is returned
	 */
	public final boolean hasChildren() {
		return fChildren != null && ! fChildren.isEmpty();
	}

	/**
	 * Returns the edit's children. If the edit doesn't have any 
	 * children an empty array is returned.
	 * 
	 * @return the edit's children
	 */
	public final TextEdit[] getChildren() {
		if (fChildren == null)
			return EMPTY_ARRAY;
		return (TextEdit[])fChildren.toArray(new TextEdit[fChildren.size()]);
	}
	
	/**
	 * Returns the size of the managed children.
	 * 
	 * @return the size of the children
	 */
	public final int getChildrenSize() {
		if (fChildren == null)
			return 0;
		return fChildren.size();
	}
	
	/**
	 * Returns the text range spawned by the given array of text edits.
	 * The method requires that the given array contains at least one
	 * edit. If all edits passed are deleted the method returns <code>
	 * null</code>.
	 * 
	 * @param edits an array of edits
	 * @return the text range spawned by the given array of edits or
	 *  <code>null</code> if all edits are marked as deleted
	 */
	public static IRegion getCoverage(TextEdit[] edits) {
		Assert.isTrue(edits != null && edits.length > 0);
			
		int offset= Integer.MAX_VALUE;
		int end= Integer.MIN_VALUE;
		int deleted= 0;
		for (int i= 0; i < edits.length; i++) {
			TextEdit edit= edits[i];
			if (edit.isDeleted()) {
				deleted++;
			} else {
				offset= Math.min(offset, edit.getOffset());
				end= Math.max(end, edit.getExclusiveEnd());
			}
		}
		if (edits.length == deleted) {
			return null;
		} else {
			return new Region(offset, end - offset);
		}
	}
		
	/*
	 * Hook called before this edit gets added to the passed 
	 * parent.
	 */	
	/* package */ void aboutToBeAdded(TextEdit parent) {
	}	
	
	//---- Object methods ------------------------------------------------------

	/**
	 * The <code>Edit</code> implementation of this <code>Object</code>
	 * method uses object identity (==).
	 * 
	 * @param obj the other object
	 * @return <code>true</code> iff <code>this == obj</code>; otherwise
	 *  <code>false</code> is returned
	 * 
	 * @see Object#equals(java.lang.Object)
	 */
	public final boolean equals(Object obj) {
		return this == obj; // equivalent to Object.equals
	}
	
	/**
	 * The <code>Edit</code> implementation of this <code>Object</code>
	 * method calls uses <code>Object#hashCode()</code> to compute its
	 * hash code.
	 * 
	 * @return the object's hash code value
	 * 
	 * @see Object#hashCode()
	 */
	public final int hashCode() {
		return super.hashCode();
	}
	
	/* non Java-doc
	 * @see java.lang.Object#toString()
	 */
	public String toString() {
		StringBuffer buffer= new StringBuffer("{"); //$NON-NLS-1$
		String name= getClass().getName();
		int index= name.lastIndexOf('.');
		if (index != -1) {
			 buffer.append(name.substring(index + 1));
		} else {
			buffer.append(name);
		}
		buffer.append(" } "); //$NON-NLS-1$
		if (isDeleted()) {
			buffer.append("[deleted]"); //$NON-NLS-1$
		} else {
			buffer.append("["); //$NON-NLS-1$
			buffer.append(fOffset);
			buffer.append(","); //$NON-NLS-1$
			buffer.append(fLength);
			buffer.append("]"); //$NON-NLS-1$
		}
		return buffer.toString();
	}
	
	//---- Copying -------------------------------------------------------------
	
	/**
	 * Creates a deep copy of the edit tree rooted at this
	 * edit.
	 * 
	 * @return a deep copy of the edit tree
	 * @see #doCopy() 
	 */
	public final TextEdit copy() {
		TextEditCopier copier= new TextEditCopier(this);
		return copier.perform();
	}
		
	/**
	 * Creates and returns a copy of this edit. The copy method should be 
	 * implemented in a way so that the copy can executed without causing
	 * any harm to the original edit. Implementors of this method are
	 * responsible for creating deep or shallow copies of referenced
	 * object to fullfil this requirement.
	 * <p>
	 * Implementers of this method should use the copy constructor <code>
	 * Edit#Edit(Edit source) to initialize the edit part of the copy.
	 * Implementors aren't responsible to actually copy the children or
	 * to set the right parent.
	 * <p>
	 * This method <b>should not be called</b> from outside the framework.
	 * Please use <code>copy</code> to create a copy of a edit tree.
	 * 
	 * @return a copy of this edit.
	 * @see #copy()
	 * @see #postProcessCopy(TextEditCopier)
	 * @see TextEditCopier
	 */
	protected abstract TextEdit doCopy();
	
	/**
	 * This method is called on every edit of the copied tree to do some
	 * postprocessing like connected an edit to a different edit in the tree.
	 * <p>
	 * This default implementation does nothing
	 * 
	 * @param copier the copier that manages a map between original and
	 *  copied edit.
	 * @see TextEditCopier
	 */
	protected void postProcessCopy(TextEditCopier copier) {
	}
	
	//---- Visitor support -------------------------------------------------
	
	/**
	 * Accepts the given visitor on a visit of the current edit.
	 * 
	 * @param visitor the visitor object
	 * @exception IllegalArgumentException if the visitor is null
	 */
	public final void accept(TextEditVisitor visitor) {
		Assert.isNotNull(visitor);
		// begin with the generic pre-visit
		visitor.preVisit(this);
		// dynamic dispatch to internal method for type-specific visit/endVisit
		accept0(visitor);
		// end with the generic post-visit
		visitor.postVisit(this);
	}
	
	/**
	 * Accepts the given visitor on a type-specific visit of the current edit.
	 * This method must be implemented in all concrete text edits.
	 * <p>
	 * General template for implementation on each concrete TextEdit class:
	 * <pre>
	 * <code>
	 * boolean visitChildren = visitor.visit(this);
	 * if (visitChildren) {
	 *    acceptChildren(visitor);
	 * }
	 * </code>
	 * </pre>
	 * Note that the caller (<code>accept</code>) takes care of invoking
	 * <code>visitor.preVisit(this)</code> and <code>visitor.postVisit(this)</code>.
	 * </p>
	 * 
	 * @param visitor the visitor object
	 */
	protected abstract void accept0(TextEditVisitor visitor);
	
	
	/**
	 * Accepts the given visitor on the edits children.
	 * <p>
	 * This method must be used by the concrete implementations of
	 * <code>accept</code> to traverse list-values properties; it
	 * encapsulates the proper handling of on-the-fly changes to the list.
	 * </p>
	 * 
	 * @param visitor the visitor object
	 */
	protected final void acceptChildren(TextEditVisitor visitor) {
		if (fChildren == null)
			return;
		Iterator iterator= fChildren.iterator();
		while (iterator.hasNext()) {
			TextEdit curr= (TextEdit) iterator.next();
			curr.accept(visitor);
		}
	}
	
	//---- Execution -------------------------------------------------------
	
	/**
	 * Applies the edit tree rooted by this edit to the given document. To check
	 * if the edit tree can be applied to the document either catch <code>
	 * MalformedTreeException</code> or use <code>TextEditProcessor</code> to
	 * execute an edit tree.
	 * 
	 * @param document the document to be manipulated
	 * @param style flags controlling the execution of the edit tree. Valid
	 *  flags are: <code>CREATE_UNDO</code> and </code>UPDATE_REGIONS</code>.
	 * @return a undo edit, if <code>CREATE_UNDO</code> is specified. Otherwise
	 *  <code>null</code> is returned.
	 * 
	 * @exception MalformedTreeException is thrown if the tree isn't
	 *  in a valid state. This exception is thrown before any edit
	 *  is executed. So the document is still in its original state.
	 * @exception BadLocationException is thrown if one of the edits
	 *  in the tree can't be executed. The state of the document is
	 *  undefined if this exception is thrown.
	 * 
	 * @see #checkIntegrity()
	 * @see #perform(IDocument)
	 * @see TextEditProcessor#performEdits()
	 */
	public final UndoEdit apply(IDocument document, int style) throws MalformedTreeException, BadLocationException {
		try {
			TextEditProcessor processor= new TextEditProcessor(document, this, style);
			return processor.performEdits();
		} finally {
			// unconnect from text edit processor
			fParent= null;
		}
	}
	
	/**
	 * Applies the edit tree rooted by this edit to the given document. This
	 * method is a convenience method for <code>apply(document, CREATE_UNDO | UPDATE_REGIONS)
	 * </code>
	 * 
	 * @see #apply(IDocument, int)
	 */
	public final UndoEdit apply(IDocument document) throws MalformedTreeException, BadLocationException {
		return apply(document, CREATE_UNDO | UPDATE_REGIONS);
	}

	/* package */ UndoEdit dispatchPerformEdits(TextEditProcessor processor) throws BadLocationException {
		return processor.executeDo();
	}
	
	/* package */ void dispatchCheckIntegrity(TextEditProcessor processor) throws MalformedTreeException {
		processor.checkIntegrityDo();
	}
	
	//---- internal state accessors ----------------------------------------------------------
	
	/* package */ void internalSetParent(TextEdit parent) {
		if (parent != null)
			Assert.isTrue(fParent == null);
		fParent= parent;
	}
	
	/* package */ void internalSetOffset(int offset) {
		Assert.isTrue(offset >= 0);
		fOffset= offset;
	}
	
	/* package */ void internalSetLength(int length) {
		Assert.isTrue(length >= 0);
		fLength= length;
	}
			
	/* package */ List internalGetChildren() {
		return fChildren;
	}
	
	/* package */ void internalSetChildren(List children) {
		fChildren= children;
	}
	
	/* package */ void internalAdd(TextEdit child) throws MalformedTreeException {
		child.aboutToBeAdded(this);
		if (child.isDeleted())
			throw new MalformedTreeException(this, child, TextEditMessages.getString("TextEdit.deleted_edit")); //$NON-NLS-1$
		if (!covers(child))
			throw new MalformedTreeException(this, child, TextEditMessages.getString("TextEdit.range_outside")); //$NON-NLS-1$
		if (fChildren == null) {
			fChildren= new ArrayList(2);
		}
		int index= computeInsertionIndex(child);
		fChildren.add(index, child);
		child.internalSetParent(this);
	}
	
	private int computeInsertionIndex(TextEdit edit) {
		int size= fChildren.size();
		if (size == 0)
			return 0;
		TextEdit last= (TextEdit)fChildren.get(size - 1);
		if (last.getExclusiveEnd() <= edit.getOffset())
			return size;
		try {
			return -Collections.binarySearch(fChildren, edit,INSERTION_COMPARATOR) -1;
		} catch(MalformedTreeException e) {
			e.setParent(this);
			throw e;
		}
	}
		
	//---- Offset & Length updating -------------------------------------------------
	
	/**
	 * Adjusts the edits offset according to the given
	 * delta. This method doesn't update any children.
	 * 
	 * @param delta the delta of the text replace operation
	 */
	/* package */ void adjustOffset(int delta) {
		if (isDeleted())
			return;
		fOffset+= delta;
		Assert.isTrue(fOffset >= 0);
	}
	
	/**
	 * Adjusts the edits length according to the given
	 * delta. This method doesn't update any children.
	 * 
	 * @param delta the delta of the text replace operation
	 */
	/* package */ void adjustLength(int delta) {
		if (isDeleted())
			return;
		fLength+= delta;
		Assert.isTrue(fLength >= 0);
	}
	
	/** 
	 * Marks the edit as deleted. This method doesn't update
	 * any children.
	 */	
	/* package */ void markAsDeleted() {
		fOffset= DELETED_VALUE;
		fLength= DELETED_VALUE;
	}
	
	//---- Edit processing ----------------------------------------------
	
	/* package */ int traverseConsistencyCheck(TextEditProcessor processor, IDocument document, List sourceEdits) {
		int result= 0;
		if (fChildren != null) {
			for (int i= fChildren.size() - 1; i >= 0; i--) {
				TextEdit child= (TextEdit)fChildren.get(i);
				result= Math.max(result, child.traverseConsistencyCheck(processor, document, sourceEdits));
			}
		}
		if (processor.considerEdit(this)) {
			performConsistencyCheck(processor, document);
		}
		return result;
	}
	
	/* package */ void performConsistencyCheck(TextEditProcessor processor, IDocument document) {
	}
	
	/* package */ void traverseSourceComputation(TextEditProcessor processor, IDocument document) {
	}
	
	/* package */ void performSourceComputation(TextEditProcessor processor, IDocument document) {
	}
	
	/* package */ int traverseDocumentUpdating(TextEditProcessor processor, IDocument document) throws BadLocationException {
		int delta= 0;
		if (fChildren != null) {
			for (int i= fChildren.size() - 1; i >= 0; i--) {
				TextEdit child= (TextEdit)fChildren.get(i);
				delta+= child.traverseDocumentUpdating(processor, document);
			}
		}
		if (processor.considerEdit(this)) {
			if (delta != 0)
				adjustLength(delta);
			int r= performDocumentUpdating(document);
			if (r != 0)
				adjustLength(r);
			delta+= r;
		}
		return delta;
	}
	
	/* package */ abstract int performDocumentUpdating(IDocument document) throws BadLocationException;
	
	/* package */ int traverseRegionUpdating(TextEditProcessor processor, IDocument document, int accumulatedDelta, boolean delete) {
		performRegionUpdating(accumulatedDelta, delete);
		if (fChildren != null) {
			boolean childDelete= delete || deleteChildren();
			for (Iterator iter= fChildren.iterator(); iter.hasNext();) {
				TextEdit child= (TextEdit)iter.next();
				accumulatedDelta= child.traverseRegionUpdating(processor, document, accumulatedDelta, childDelete);
			}
		}
		return accumulatedDelta + fDelta;
	}
	
	/* package */ void performRegionUpdating(int accumulatedDelta, boolean delete) {
		if (delete)
			markAsDeleted();
		else
			adjustOffset(accumulatedDelta);
	}
	
	/* package */ abstract boolean deleteChildren();

	/* package */ void moveTree(int delta) {
		adjustOffset(delta);
		if (fChildren != null) {
			for (Iterator iter= fChildren.iterator(); iter.hasNext();) {
				((TextEdit)iter.next()).moveTree(delta);
			}
		}
	}
	
	/* package */ void deleteTree() {
		markAsDeleted();
		if (fChildren != null) {
			for (Iterator iter= fChildren.iterator(); iter.hasNext();) {
				TextEdit child= (TextEdit)iter.next();
				child.deleteTree();
			}
		}
	}
}

