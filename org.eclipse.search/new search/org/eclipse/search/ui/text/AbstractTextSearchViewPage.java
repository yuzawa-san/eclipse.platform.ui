/*******************************************************************************
 * Copyright (c) 2000, 2004 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials 
 * are made available under the terms of the Common Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/cpl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.search.ui.text;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.ISafeRunnable;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.GroupMarker;
import org.eclipse.jface.action.IContributionManager;
import org.eclipse.jface.action.IMenuListener;
import org.eclipse.jface.action.IMenuManager;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.jface.action.Separator;
import org.eclipse.jface.dialogs.ErrorDialog;
import org.eclipse.jface.dialogs.IDialogSettings;
import org.eclipse.jface.text.Position;
import org.eclipse.jface.util.Assert;
import org.eclipse.jface.viewers.DecoratingLabelProvider;
import org.eclipse.jface.viewers.IBaseLabelProvider;
import org.eclipse.jface.viewers.IOpenListener;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.ISelectionChangedListener;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.OpenEvent;
import org.eclipse.jface.viewers.SelectionChangedEvent;
import org.eclipse.jface.viewers.StructuredViewer;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.search.internal.ui.CopyToClipboardAction;
import org.eclipse.search.internal.ui.SearchPlugin;
import org.eclipse.search.internal.ui.SearchPluginImages;
import org.eclipse.search.ui.IContextMenuConstants;
import org.eclipse.search.ui.IQueryListener;
import org.eclipse.search.ui.ISearchQuery;
import org.eclipse.search.ui.ISearchResult;
import org.eclipse.search.ui.ISearchResultListener;
import org.eclipse.search.ui.ISearchResultPage;
import org.eclipse.search.ui.ISearchResultViewPart;
import org.eclipse.search.ui.NewSearchUI;
import org.eclipse.search.ui.SearchResultEvent;
import org.eclipse.search2.internal.ui.InternalSearchUI;
import org.eclipse.search2.internal.ui.SearchMessages;
import org.eclipse.search2.internal.ui.basic.views.CollapseAllAction;
import org.eclipse.search2.internal.ui.basic.views.ExpandAllAction;
import org.eclipse.search2.internal.ui.basic.views.INavigate;
import org.eclipse.search2.internal.ui.basic.views.RemoveAllMatchesAction;
import org.eclipse.search2.internal.ui.basic.views.RemoveMatchAction;
import org.eclipse.search2.internal.ui.basic.views.RemoveSelectedMatchesAction;
import org.eclipse.search2.internal.ui.basic.views.SetLayoutAction;
import org.eclipse.search2.internal.ui.basic.views.ShowNextResultAction;
import org.eclipse.search2.internal.ui.basic.views.ShowPreviousResultAction;
import org.eclipse.search2.internal.ui.basic.views.TableViewerNavigator;
import org.eclipse.search2.internal.ui.basic.views.TreeViewerNavigator;
import org.eclipse.search2.internal.ui.text.AnnotationManager;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.ui.IActionBars;
import org.eclipse.ui.IMemento;
import org.eclipse.ui.IWorkbenchActionConstants;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.actions.ActionFactory;
import org.eclipse.ui.part.IPageSite;
import org.eclipse.ui.part.Page;
import org.eclipse.ui.part.PageBook;
import org.eclipse.ui.progress.UIJob;
/**
 * An abstract base implementation for classes showing
 * <code>AbstractTextSearchResult</code> instances. This class assumes that
 * the input element set via {@link AbstractTextSearchViewPage#setInput(ISearchResult,Object)}
 * is a subclass of {@link AbstractTextSearchResult}.
 * This result page supports a tree and/or a table presentation of search
 * results. Subclasses can determine which presentations they want to support at
 * construction time by passing the appropriate flags.
 * Subclasses must customize the viewers for each presentation with a label
 * provider and a content provider. <br>
 * Changes in the search result are handled by updating the viewer in the
 * <code>elementsChanged()</code> and <code>clear()</code> methods.
 * 
 * @since 3.0
 */
public abstract class AbstractTextSearchViewPage extends Page implements ISearchResultPage {
	private class UpdateUIJob extends UIJob {
		
		public UpdateUIJob() {
			super(SearchMessages.getString("AbstractTextSearchViewPage.update_job.name")); //$NON-NLS-1$
			setSystem(true);
		}
		
		public IStatus runInUIThread(IProgressMonitor monitor) {
			fViewPart.updateLabel();
			runBatchedUpdates();
			if (hasMoreUpdates() || isQueryRunning()) {
				schedule(500);
			} else {
				fIsUIUpdateScheduled= false;
				turnOnDecoration();
			}
			return Status.OK_STATUS;
		}
		
		/* 
		 * Undocumented for testing only. Used to find UpdateUIJobs.
		 */
		public boolean belongsTo(Object family) {
			return family == AbstractTextSearchViewPage.this;
		}

	}

	private transient boolean  fIsUIUpdateScheduled= false;
	private static final String KEY_LAYOUT = "org.eclipse.search.resultpage.layout"; //$NON-NLS-1$
	
	protected static final Match[] EMPTY_MATCH_ARRAY= new Match[0];
	
	private StructuredViewer fViewer;
	private Composite fViewerContainer;
	private Control fBusyLabel;
	private PageBook fPagebook;
	private boolean fIsBusyShown;
	private ISearchResultViewPart fViewPart;
	private Set fBatchedUpdates;
	private ISearchResultListener fListener;
	private IQueryListener fQueryListener;
	private MenuManager fMenu;
	private ISearchResult fInput;
	// Actions
	private Action fCopyToClipboardAction;
	private Action fRemoveSelectedMatches;
	private Action fRemoveCurrentMatch;
	private Action fRemoveAllResultsAction;
	private Action fShowNextAction;
	private Action fShowPreviousAction;
	private SetLayoutAction fFlatAction;
	private SetLayoutAction fHierarchicalAction;
	private int fCurrentLayout;
	private int fCurrentMatchIndex = 0;
	private String fId;
	private int fSupportedLayouts;
	/**
	 * Flag (<code>value 1</code>) denoting tree layout.
	 */
	public static final int FLAG_LAYOUT_FLAT = 1;
	/**
	 * Flag (<code>value 2</code>) denoting flat list layout.
	 */
	public static final int FLAG_LAYOUT_TREE = 2;
	
	/**
	 * This constructor must be passed a combination of layout flags combined
	 * with bitwise or. At least one flag must be passed in (i.e. 0 is not a
	 * permitted value).
	 * 
	 * @param supportedLayouts
	 *            flags determining which layout options this page supports.
	 *            Must not be 0
	 * @see #FLAG_LAYOUT_FLAT
	 * @see #FLAG_LAYOUT_TREE
	 */
	protected AbstractTextSearchViewPage(int supportedLayouts) {
		fSupportedLayouts = supportedLayouts;
		initLayout();
		fRemoveAllResultsAction = new RemoveAllMatchesAction(this);
		fRemoveSelectedMatches = new RemoveSelectedMatchesAction(this);
		fRemoveCurrentMatch = new RemoveMatchAction(this);
		fShowNextAction = new ShowNextResultAction(this);
		fShowPreviousAction = new ShowPreviousResultAction(this);
		createLayoutActions();
		fBatchedUpdates = new HashSet();
		fListener = new ISearchResultListener() {
			public void searchResultChanged(SearchResultEvent e) {
				handleSearchResultsChanged(e);
			}
		};
	}

	private void initLayout() {
		if (supportsFlatLayout())
			fCurrentLayout = FLAG_LAYOUT_FLAT;
		else
			fCurrentLayout = FLAG_LAYOUT_TREE;
	}

	/**
	 * Constructs this page with the default layout flags.
	 * 
	 * @see AbstractTextSearchViewPage#AbstractTextSearchViewPage(int)
	 */
	protected AbstractTextSearchViewPage() {
		this(FLAG_LAYOUT_FLAT | FLAG_LAYOUT_TREE);
	}

	private void createLayoutActions() {
		if (countBits(fSupportedLayouts) > 1) {
			fFlatAction = new SetLayoutAction(this, SearchMessages.getString("AbstractTextSearchViewPage.flat_layout.label"), SearchMessages.getString("AbstractTextSearchViewPage.flat_layout.tooltip"), FLAG_LAYOUT_FLAT); //$NON-NLS-1$ //$NON-NLS-2$
			fHierarchicalAction = new SetLayoutAction(this, SearchMessages.getString("AbstractTextSearchViewPage.hierarchical_layout.label"), SearchMessages.getString("AbstractTextSearchViewPage.hierarchical_layout.tooltip"), FLAG_LAYOUT_TREE); //$NON-NLS-1$ //$NON-NLS-2$
			SearchPluginImages.setImageDescriptors(fFlatAction, SearchPluginImages.T_LCL, SearchPluginImages.IMG_LCL_SEARCH_FLAT_LAYOUT);
			SearchPluginImages.setImageDescriptors(fHierarchicalAction, SearchPluginImages.T_LCL, SearchPluginImages.IMG_LCL_SEARCH_HIERARCHICAL_LAYOUT);
		}
	}
	
	private int countBits(int layoutFlags) {
		int bitCount = 0;
		for (int i = 0; i < 32; i++) {
			if (layoutFlags % 2 == 1)
				bitCount++;
			layoutFlags >>= 1;
		}
		return bitCount;
	}

	private boolean supportsFlatLayout() {
		return isLayoutSupported(FLAG_LAYOUT_FLAT);
	}

	/**
	 * Returns a dialog settings object for this search result page. There will be
	 * one dialog settings object per search result page id.
	 * 
	 * @return the dialog settings for this search result page
	 * @see AbstractTextSearchViewPage#getID()
	 */
	protected IDialogSettings getSettings() {
		IDialogSettings parent = SearchPlugin.getDefault().getDialogSettings();
		IDialogSettings settings = parent.getSection(getID());
		if (settings == null)
			settings = parent.addNewSection(getID());
		return settings;
	}

	/**
	 * {@inheritDoc}
	 */
	public void setID(String id) {
		fId = id;
	}

	/**
	 * {@inheritDoc}
	 */
	public String getID() {
		return fId;
	}
	
	/**
	 * {@inheritDoc}
	 */
	public String getLabel() {
		AbstractTextSearchResult result= getInput();
		if (result == null)
			return ""; //$NON-NLS-1$
		return result.getLabel();
	}

	/**
	 * Opens an editor on the given element and selects the given range of text.
	 * If a search results implements a <code>IFileMatchAdapter</code>, match
	 * locations will be tracked and the current match range will be passed into
	 * this method.
	 * 
	 * @param match
	 *            the match to show
	 * @param currentOffset
	 *            the current start offset of the match
	 * @param currentLength
	 *            the current length of the selection
	 * @throws PartInitException
	 *             if an editor can't be opened
	 * 
	 * @see org.eclipse.core.filebuffers.ITextFileBufferManager
	 * @see IFileMatchAdapter
	 */
	protected abstract void showMatch(Match match, int currentOffset, int currentLength) throws PartInitException;

	/**
	 * This method is called whenever the set of matches for the given elements
	 * changes. This method is guaranteed to be called in the UI thread. Note
	 * that this notification is asynchronous. i.e. further changes may have
	 * occurred by the time this method is called. They will be described in a
	 * future call.
	 * 
	 * @param objects
	 *            array of objects that has to be refreshed
	 */
	protected abstract void elementsChanged(Object[] objects);

	/**
	 * This method is called whenever all elements have been removed from the
	 * shown <code>AbstractSearchResult</code>. This method is guaranteed to
	 * be called in the UI thread. Note that this notification is asynchronous.
	 * i.e. further changes may have occurred by the time this method is called.
	 * They will be described in a future call.
	 */
	protected abstract void clear();

	/**
	 * Configures the given viewer. Implementers have to set at least a content
	 * provider and a label provider. This method may be called if the page was
	 * constructed with the flag <code>FLAG_LAYOUT_TREE</code>.
	 * 
	 * @param viewer the viewer to be configured
	 */
	protected abstract void configureTreeViewer(TreeViewer viewer);

	/**
	 * Configures the given viewer. Implementers have to set at least a content
	 * provider and a label provider. This method may be called if the page was
	 * constructed with the flag <code>FLAG_LAYOUT_FLAT</code>.
	 * 
	 * @param viewer the viewer to be configured
	 */
	protected abstract void configureTableViewer(TableViewer viewer);

	private static void createStandardGroups(IContributionManager menu) {
		menu.add(new Separator(IContextMenuConstants.GROUP_NEW));
		menu.add(new GroupMarker(IContextMenuConstants.GROUP_GOTO));
		menu.add(new GroupMarker(IContextMenuConstants.GROUP_OPEN));
		menu.add(new Separator(IContextMenuConstants.GROUP_SHOW));
		menu.add(new Separator(IContextMenuConstants.GROUP_BUILD));
		menu.add(new Separator(IContextMenuConstants.GROUP_REORGANIZE));
		menu.add(new Separator(IContextMenuConstants.GROUP_REMOVE_MATCHES));
		menu.add(new GroupMarker(IContextMenuConstants.GROUP_GENERATE));
		menu.add(new Separator(IContextMenuConstants.GROUP_SEARCH));
		menu.add(new Separator(IWorkbenchActionConstants.MB_ADDITIONS));
		menu.add(new Separator(IContextMenuConstants.GROUP_VIEWER_SETUP));
		menu.add(new Separator(IContextMenuConstants.GROUP_PROPERTIES));
	}

	/**
	 * Fills the context menu for this page. Subclasses may override this
	 * method.
	 * 
	 * @param mgr the menu manager representing the context menu
	 */
	protected void fillContextMenu(IMenuManager mgr) {
		mgr.appendToGroup(IContextMenuConstants.GROUP_ADDITIONS, fCopyToClipboardAction);
		mgr.appendToGroup(IContextMenuConstants.GROUP_SHOW, fShowNextAction);
		mgr.appendToGroup(IContextMenuConstants.GROUP_SHOW, fShowPreviousAction);
		if (getCurrentMatch() != null)
			mgr.appendToGroup(IContextMenuConstants.GROUP_REMOVE_MATCHES, fRemoveCurrentMatch);
		if (!getViewer().getSelection().isEmpty())
			mgr.appendToGroup(IContextMenuConstants.GROUP_REMOVE_MATCHES, fRemoveSelectedMatches);
		mgr.appendToGroup(IContextMenuConstants.GROUP_REMOVE_MATCHES, fRemoveAllResultsAction);
	}

	/**
	 * {@inheritDoc}
	 */
	public void createControl(Composite parent) {
		fQueryListener = createQueryListener();
		fMenu = new MenuManager("#PopUp"); //$NON-NLS-1$
		fMenu.setRemoveAllWhenShown(true);
		fMenu.setParent(getSite().getActionBars().getMenuManager());
		fMenu.addMenuListener(new IMenuListener() {
			public void menuAboutToShow(IMenuManager mgr) {
				createStandardGroups(mgr);
				fillContextMenu(mgr);
				fViewPart.fillContextMenu(mgr);
			}
		});
		fPagebook = new PageBook(parent, SWT.NULL);
		fPagebook.setLayoutData(new GridData(GridData.FILL_BOTH));
		fBusyLabel = createBusyControl();
		fViewerContainer = new Composite(fPagebook, SWT.NULL);
		fViewerContainer.setLayoutData(new GridData(GridData.FILL_BOTH));
		fViewerContainer.setSize(100, 100);
		fViewerContainer.setLayout(new FillLayout());
		createViewer(fViewerContainer, fCurrentLayout);
		showBusyLabel(fIsBusyShown);
		NewSearchUI.addQueryListener(fQueryListener);
	}

	private Control createBusyControl() {
		Table busyLabel = new Table(fPagebook, SWT.NULL);
		TableItem item = new TableItem(busyLabel, SWT.NULL);
		item.setText(SearchMessages.getString("AbstractTextSearchViewPage.searching.label")); //$NON-NLS-1$
		busyLabel.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		return busyLabel;
	}

	private synchronized void scheduleUIUpdate() {
		if (!fIsUIUpdateScheduled) {
			fIsUIUpdateScheduled= true;
			new UpdateUIJob().schedule();
		}
	}

	private IQueryListener createQueryListener() {
		return new IQueryListener() {
			public void queryAdded(ISearchQuery query) {
				// ignore
			}

			public void queryRemoved(ISearchQuery query) {
				// ignore
			}

			public void queryStarting(final ISearchQuery query) {
				final Runnable runnable1 = new Runnable() {
					public void run() {
						updateBusyLabel();
						AbstractTextSearchResult result = getInput();

						if (result == null || !result.getQuery().equals(query)) {
							return;
						}
						turnOffDecoration();
						scheduleUIUpdate();
					}


				};
				asyncExec(runnable1);
			}

			public void queryFinished(final ISearchQuery query) {
				final Runnable runnable2 = new Runnable() {
					public void run() {
						updateBusyLabel();
						AbstractTextSearchResult result = getInput();

						if (result == null || !result.getQuery().equals(query)) {
							return;
						}
						
						if (fViewer.getSelection().isEmpty()) {
							navigateNext(true);
						}
					}
				};
				asyncExec(runnable2);
			}
		};
	}

	private void updateBusyLabel() {
		AbstractTextSearchResult result = getInput();
		boolean shouldShowBusy = result != null && NewSearchUI.isQueryRunning(result.getQuery()) && result.getMatchCount() == 0;
		if (shouldShowBusy == fIsBusyShown)
			return;
		fIsBusyShown = shouldShowBusy;
		showBusyLabel(fIsBusyShown);
	}

	private void showBusyLabel(boolean shouldShowBusy) {
		if (shouldShowBusy)
			fPagebook.showPage(fBusyLabel);
		else
			fPagebook.showPage(fViewerContainer);
	}

	/**
	 * Determines whether a certain layout is supported by this search result
	 * page.
	 * 
	 * @param layout the layout to test for
	 * @return whether the given layout is supported or not
	 * 
	 * @see AbstractTextSearchViewPage#AbstractTextSearchViewPage(int)
	 */
	public boolean isLayoutSupported(int layout) {
		return (layout & fSupportedLayouts) == layout;
	}

	/**
	 * Sets the layout of this search result page. The layout must be on of
	 * <code>FLAG_LAYOUT_FLAT</code> or <code>FLAG_LAYOUT_TREE</code> and
	 * it must be one of the values passed during construction of this search 
	 * result page.
	 * 
	 * @see AbstractTextSearchViewPage#isLayoutSupported(int)
	 */
	public void setLayout(int layout) {
		Assert.isTrue(countBits(layout) == 1);
		Assert.isTrue(isLayoutSupported(layout));
		if (countBits(fSupportedLayouts) < 2)
			return;
		if (fCurrentLayout == layout)
			return;
		fCurrentLayout = layout;
		ISelection selection = fViewer.getSelection();
		ISearchResult result = disconnectViewer();
		fViewer.getControl().dispose();
		fViewer = null;
		createViewer(fViewerContainer, layout);
		fViewerContainer.layout(true);
		connectViewer(result);
		fViewer.setSelection(selection, true);
		getSettings().put(KEY_LAYOUT, layout);
	}

	private void updateLayoutActions() {
		if (fFlatAction != null)
			fFlatAction.setChecked(fCurrentLayout == fFlatAction.getLayout());
		if (fHierarchicalAction != null)
			fHierarchicalAction.setChecked(fCurrentLayout == fHierarchicalAction.getLayout());
	}

	/**
	 * Return the layout this page is currently using.
	 * 
	 * @return the layout this page is currently using
	 * 
	 * @see #FLAG_LAYOUT_FLAT
	 * @see #FLAG_LAYOUT_TREE
	 */
	public int getLayout() {
		return fCurrentLayout;
	}

	private void createViewer(Composite parent, int layout) {
		if ((layout & FLAG_LAYOUT_FLAT) != 0) {
			TableViewer viewer = createTableViewer(parent);
			fViewer = viewer;
			configureTableViewer(viewer);
		} else if ((layout & FLAG_LAYOUT_TREE) != 0) {
			TreeViewer viewer = createTreeViewer(parent);
			fViewer = viewer;
			configureTreeViewer(viewer);
		}
		IToolBarManager tbm = getSite().getActionBars().getToolBarManager();
		tbm.removeAll();
		createStandardGroups(tbm);
		fillToolbar(tbm);
		tbm.update(false);
		fViewer.addOpenListener(new IOpenListener() {
			public void open(OpenEvent event) {
				boolean hasCurrentMatch = showCurrentMatch();
				if (event.getViewer() instanceof TreeViewer && event.getSelection() instanceof IStructuredSelection) {
					TreeViewer tv = (TreeViewer) event.getViewer();
					Object element = ((IStructuredSelection) event.getSelection()).getFirstElement();
					tv.setExpandedState(element, !tv.getExpandedState(element));
					if (!hasCurrentMatch && getDisplayedMatchCount(element) > 0)
						gotoNextMatch();
					return;
				} else if (!hasCurrentMatch) {
					gotoNextMatch();
				}
			}
		});
		fViewer.addSelectionChangedListener(new ISelectionChangedListener() {
			public void selectionChanged(SelectionChangedEvent event) {
				fCurrentMatchIndex = -1;
				fRemoveSelectedMatches.setEnabled(!event.getSelection().isEmpty());
			}
		});
		Menu menu = fMenu.createContextMenu(fViewer.getControl());
		fViewer.getControl().setMenu(menu);
		getSite().setSelectionProvider(fViewer);
		// Register menu
		getSite().registerContextMenu(fViewPart.getViewSite().getId(), fMenu, fViewer);
		updateLayoutActions();
	}

	/**
	 * Creates the tree viewer to be shown on this page. Clients may override
	 * this method.
	 * 
	 * @param parent the parent widget
	 * @return returns a newly created <code>TreeViewer</code>.
	 */
	protected TreeViewer createTreeViewer(Composite parent) {
		return new TreeViewer(parent, SWT.MULTI | SWT.H_SCROLL | SWT.V_SCROLL);
	}

	/**
	 * Creates the table viewer to be shown on this page. Clients may override
	 * this method.
	 * 
	 * @param parent the parent widget
	 * @return returns a newly created <code>TableViewer</code>
	 */
	protected TableViewer createTableViewer(Composite parent) {
		return new TableViewer(parent, SWT.MULTI | SWT.H_SCROLL | SWT.V_SCROLL);
	}

	/**
	 * {@inheritDoc}
	 */
	public void setFocus() {
		Control control = fViewer.getControl();
		if (control != null && !control.isDisposed())
			control.setFocus();
	}

	/**
	 * {@inheritDoc}
	 */
	public Control getControl() {
		return fPagebook;
	}

	/**
	 * {@inheritDoc}
	 */
	public void setInput(ISearchResult search, Object viewState) {
		ISearchResult oldSearch = disconnectViewer();
		if (oldSearch != null)
			oldSearch.removeListener(fListener);
		AnnotationManager.searchResultActivated(getSite().getWorkbenchWindow(), (AbstractTextSearchResult) search);
		fInput= search;
		if (search != null) {
			search.addListener(fListener);
			connectViewer(search);
			if (viewState instanceof ISelection)
				fViewer.setSelection((ISelection) viewState, true);
			else
				gotoNextMatch();
		}
		updateBusyLabel();
		turnOffDecoration();
		scheduleUIUpdate();
	}

	/**
	 * {@inheritDoc}
	 */
	public Object getUIState() {
		return fViewer.getSelection();
	}

	private void connectViewer(ISearchResult search) {
		fCopyToClipboardAction = new CopyToClipboardAction(fViewer);
		fViewer.setInput(search);
	}

	private ISearchResult disconnectViewer() {
		ISearchResult result = (ISearchResult) fViewer.getInput();
		fViewer.setInput(null);
		return result;
	}

	/**
	 * Returns the viewer currently used in this page.
	 * 
	 * @return the currently used viewer or <code>null</code> if none has been
	 *         created yet.
	 */
	protected StructuredViewer getViewer() {
		return fViewer;
	}

	private void showMatch(final Match match) {
		ISafeRunnable runnable = new ISafeRunnable() {
			public void handleException(Throwable exception) {
				if (exception instanceof PartInitException) {
					PartInitException pie = (PartInitException) exception;
					ErrorDialog.openError(getSite().getShell(), SearchMessages.getString("DefaultSearchViewPage.show_match"), SearchMessages.getString("DefaultSearchViewPage.error.no_editor"), pie.getStatus()); //$NON-NLS-1$ //$NON-NLS-2$
				}
			}

			public void run() throws Exception {
				Position currentPosition = InternalSearchUI.getInstance().getPositionTracker().getCurrentPosition(match);
				if (currentPosition != null) {
					showMatch(match, currentPosition.getOffset(), currentPosition.getLength());
				} else {
					showMatch(match, match.getOffset(), match.getLength());
				}
			}
		};
		Platform.run(runnable);
	}

	/**
	 * Returns the currently shown result.
	 * 
	 * @return the previously set result or <code>null</code>
	 * 
	 * @see AbstractTextSearchViewPage#setInput(ISearchResult, Object)
	 */
	public AbstractTextSearchResult getInput() {
		return (AbstractTextSearchResult) fInput;
	}

	/**
	 * Selects the element corresponding to the next match and shows the match
	 * in an editor. Note that this will cycle back to the first match after the
	 * last match.
	 */
	public void gotoNextMatch() {
		fCurrentMatchIndex++;
		Match nextMatch = getCurrentMatch();
		if (nextMatch == null) {
			navigateNext(true);
			fCurrentMatchIndex = 0;
		}
		showCurrentMatch();
	}

	/**
	 * Selects the element corresponding to the previous match and shows the
	 * match in an editor. Note that this will cycle back to the last match
	 * after the first match.
	 */
	public void gotoPreviousMatch() {
		fCurrentMatchIndex--;
		Match nextMatch = getCurrentMatch();
		if (nextMatch == null) {
			navigateNext(false);
			fCurrentMatchIndex = getInput().getMatchCount(getFirstSelectedElement()) - 1;
		}
		showCurrentMatch();
	}

	private void navigateNext(boolean forward) {
		INavigate navigator = null;
		if (fViewer instanceof TableViewer) {
			navigator = new TableViewerNavigator((TableViewer) fViewer);
		} else {
			navigator = new TreeViewerNavigator((TreeViewer) fViewer);
		}
		navigator.navigateNext(forward);
	}

	private boolean showCurrentMatch() {
		Match currentMatch = getCurrentMatch();
		if (currentMatch != null) {
			showMatch(currentMatch);
			return true;
		} else {
			return false;
		}
	}

	/**
	 * Returns the currently selected match.
	 * 
	 * @return the selected match or <code>null</code> if none are selected
	 */
	public Match getCurrentMatch() {
		Object element = getFirstSelectedElement();
		if (element != null) {
			Match[] matches = getDisplayedMatches(element);
			if (fCurrentMatchIndex >= 0 && fCurrentMatchIndex < matches.length)
				return matches[fCurrentMatchIndex];
		}
		return null;
	}
	
	/**
	 * Returns the matches that are currently displayed for the given element.
	 * While the default implementation just forwards to the current input
	 * search result of the page, subclasses may override this method to do
	 * filtering, etc. Any action operating on the visible matches in the search
	 * result page should use this method to get the matches for a search
	 * result (instead of asking the search result directly).
	 * 
	 * @param element
	 *            The element to get the matches for
	 * @return The matches displayed for the given element. If the current input
	 *         of this page is <code>null</code>, an empty array is returned
	 * @see AbstractTextSearchResult#getMatches(Object)
	 */
	public Match[] getDisplayedMatches(Object element) {
		AbstractTextSearchResult result= getInput();
		if (result == null)
			return EMPTY_MATCH_ARRAY;
		return result.getMatches(element);		
	}
	
	/**
	 * Returns the number of matches that are currently displayed for the given
	 * element. While the default implementation just forwards to the current
	 * input search result of the page, subclasses may override this method to
	 * do filtering, etc. Any action operating on the visible matches in the
	 * search result page should use this method to get the match count for a
	 * search result (instead of asking the search result directly).
	 * 
	 * @param element
	 *            The element to get the matches for
	 * @return The number of matches displayed for the given element. If the
	 *         current input of this page is <code>null</code>, 0 is
	 *         returned
	 * @see AbstractTextSearchResult#getMatchCount(Object)
	 */
	public int getDisplayedMatchCount(Object element) {
		AbstractTextSearchResult result= getInput();
		if (result == null)
			return 0;
		return result.getMatchCount(element);		
	}
	
	private Object getFirstSelectedElement() {
		IStructuredSelection selection = (IStructuredSelection) fViewer.getSelection();
		if (selection.size() > 0)
			return selection.getFirstElement();
		return null;
	}

	/**
	 * {@inheritDoc}
	 */
	public void dispose() {
		//disconnectViewer();
		super.dispose();
		NewSearchUI.removeQueryListener(fQueryListener);
	}

	/**
	 * {@inheritDoc}
	 */
	public void init(IPageSite pageSite) {
		super.init(pageSite);
		addLayoutActions(pageSite.getActionBars().getMenuManager());
	}

	/**
	 * Fills the toolbar contribution for this page. Subclasses may override
	 * this method.
	 * 
	 * @param tbm the tool bar manager representing the view's toolbar
	 */
	protected void fillToolbar(IToolBarManager tbm) {
		tbm.appendToGroup(IContextMenuConstants.GROUP_SHOW, fShowNextAction); //$NON-NLS-1$
		tbm.appendToGroup(IContextMenuConstants.GROUP_SHOW, fShowPreviousAction); //$NON-NLS-1$
		tbm.appendToGroup(IContextMenuConstants.GROUP_REMOVE_MATCHES, fRemoveSelectedMatches); //$NON-NLS-1$
		tbm.appendToGroup(IContextMenuConstants.GROUP_REMOVE_MATCHES, fRemoveAllResultsAction); //$NON-NLS-1$
		IActionBars actionBars = getSite().getActionBars();
		if (actionBars != null) {
			actionBars.setGlobalActionHandler(ActionFactory.NEXT.getId(), fShowNextAction);
			actionBars.setGlobalActionHandler(ActionFactory.PREVIOUS.getId(), fShowPreviousAction);
			actionBars.setGlobalActionHandler(ActionFactory.DELETE.getId(), fRemoveSelectedMatches);
		}
		if (getLayout() == FLAG_LAYOUT_TREE) {
			addTreeActions(tbm);
		}
	}

	private void addTreeActions(IToolBarManager tbm) {
		// create new actions, new viewer created
		tbm.appendToGroup(IContextMenuConstants.GROUP_VIEWER_SETUP, new ExpandAllAction((TreeViewer)getViewer()));
		tbm.appendToGroup(IContextMenuConstants.GROUP_VIEWER_SETUP, new CollapseAllAction((TreeViewer)getViewer()));
	}

	private void addLayoutActions(IMenuManager menuManager) {
		if (fFlatAction != null)
			menuManager.add(fFlatAction);
		if (fHierarchicalAction != null)
			menuManager.add(fHierarchicalAction);
	}

	/**
	 * { @inheritDoc }
	 */
	public void setViewPart(ISearchResultViewPart part) {
		fViewPart = part;
	}
	

	/**
	 * Returns the view part set with
	 * <code>setViewPart(ISearchResultViewPart)</code>.
	 * 
	 * @return The view part or <code>null</code> if the view part hasn't been
	 *         set yet (or set to null).
	 */
	protected ISearchResultViewPart getViewPart() {
		return fViewPart;
	}

	// multithreaded update handling.
	private synchronized void handleSearchResultsChanged(final SearchResultEvent e) {
		if (e instanceof MatchEvent) {
			MatchEvent me = (MatchEvent) e;
			postUpdate(me.getMatches());
		} else if (e instanceof RemoveAllEvent) {
			postClear();
		}
	}

	private synchronized void postUpdate(Match[] matches) {
		for (int i = 0; i < matches.length; i++) {
			fBatchedUpdates.add(matches[i].getElement());
		}
		scheduleUIUpdate();
	}

	private synchronized void runBatchedUpdates() {
		if (fBatchedUpdates.size() > 100) {
			Object[] hundredUpdates= new Object[100];
			Iterator elements= fBatchedUpdates.iterator();
			for (int i= 0; i < hundredUpdates.length; i++) {
				hundredUpdates[i]= elements.next();
				elements.remove();
			}
			elementsChanged(hundredUpdates);
		} else {
			elementsChanged(fBatchedUpdates.toArray());
			fBatchedUpdates.clear();
		}
		updateBusyLabel();
	}

	private void postClear() {
		asyncExec(new Runnable() {
			public void run() {
				runClear();
			}
		});
	}

	private synchronized boolean hasMoreUpdates() {
		return fBatchedUpdates.size() > 0;
	}

	private boolean isQueryRunning() {
		AbstractTextSearchResult result= getInput();
		if (result != null) {
			return NewSearchUI.isQueryRunning(result.getQuery());
		}
		return false;
	}

	private void runClear() {
		synchronized (this) {
			fBatchedUpdates.clear();
			updateBusyLabel();
		}
		clear();
	}

	private void asyncExec(Runnable runnable) {
		Control control = getControl();
		if (control != null && !control.isDisposed()) {
			Display currentDisplay = Display.getCurrent();
			if (currentDisplay == null || !currentDisplay.equals(control.getDisplay()))
				// meaning we're not executing on the display thread of the
				// control
				control.getDisplay().asyncExec(runnable);
			else
				runnable.run();
		}
	}

	/**
	 * {@inheritDoc}Subclasses may exend this method.
	 */
	public void restoreState(IMemento memento) {
		if (countBits(fSupportedLayouts) > 1) {
			try {
				fCurrentLayout = getSettings().getInt(KEY_LAYOUT);
				// workaround because the saved value may be 0
				if (fCurrentLayout == 0)
					initLayout();
			} catch (NumberFormatException e) {
				// ignore, signals no value stored.
			}
			if (memento != null) {
				Integer layout = memento.getInteger(KEY_LAYOUT);
				if (layout != null) {
					fCurrentLayout = layout.intValue();
					// workaround because the saved value may be 0
					if (fCurrentLayout == 0)
						initLayout();
				}
			}
		}
	}

	/**
	 * { @inheritDoc }
	 * Subclasses my extend this method.
	 */
	public void saveState(IMemento memento) {
		if (countBits(fSupportedLayouts) > 1) {
			memento.putInteger(KEY_LAYOUT, fCurrentLayout);
		}
	}

	/**
	 * Note: this is internal API and should not be called from clients outside
	 * of the search plug-in.
	 * <p>
	 * Removes the currently selected match. Does nothing if no match is
	 * selected.
	 * </p>
	 */
	public void internalRemoveSelected() {
		AbstractTextSearchResult result = getInput();
		if (result == null)
			return;
		StructuredViewer viewer = getViewer();
		IStructuredSelection selection = (IStructuredSelection) viewer.getSelection();
		HashSet set = new HashSet();
		if (viewer instanceof TreeViewer) {
			ITreeContentProvider cp = (ITreeContentProvider) viewer.getContentProvider();
			collectAllMatchesBelow(result, set, cp, selection.toArray());
		} else {
			collectAllMatches(result, set, selection.toArray());
		}
		Match[] matches = new Match[set.size()];
		set.toArray(matches);
		result.removeMatches(matches);
	}	

	private void collectAllMatches(AbstractTextSearchResult result, HashSet set, Object[] elements) {
		for (int j = 0; j < elements.length; j++) {
			Match[] matches = getDisplayedMatches(elements[j]);
			for (int i = 0; i < matches.length; i++) {
				set.add(matches[i]);
			}
		}
	}

	private void collectAllMatchesBelow(AbstractTextSearchResult result, Set set, ITreeContentProvider cp, Object[] elements) {
		for (int j = 0; j < elements.length; j++) {
			Match[] matches = getDisplayedMatches(elements[j]);
			for (int i = 0; i < matches.length; i++) {
				set.add(matches[i]);
			}
			Object[] children = cp.getChildren(elements[j]);
			collectAllMatchesBelow(result, set, cp, children);
		}
	}
	
	private void turnOffDecoration() {
		IBaseLabelProvider lp= fViewer.getLabelProvider();
		if (lp instanceof DecoratingLabelProvider) {
			((DecoratingLabelProvider)lp).setLabelDecorator(null);			
		}
	}

	private void turnOnDecoration() {
		IBaseLabelProvider lp= fViewer.getLabelProvider();
		if (lp instanceof DecoratingLabelProvider) {
			((DecoratingLabelProvider)lp).setLabelDecorator(PlatformUI.getWorkbench().getDecoratorManager().getLabelDecorator());			
			
		}
	}

}