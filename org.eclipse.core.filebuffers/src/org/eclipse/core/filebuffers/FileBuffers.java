/**********************************************************************
Copyright (c) 2000, 2003 IBM Corp. and others.
All rights reserved. This program and the accompanying materials
are made available under the terms of the Common Public License v1.0
which accompanies this distribution, and is available at
http://www.eclipse.org/legal/cpl-v10.html

Contributors:
	IBM Corporation - Initial implementation
**********************************************************************/
package org.eclipse.core.filebuffers;

import org.eclipse.core.internal.filebuffers.FileBuffersPlugin;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;

/**
 * Facade for the file buffers plug-in. Provides access to the
 * text file buffer manager.
 * 
 * @since 3.0
 */
public final class FileBuffers {
	
	/**
	 * Cannot be instantiated.
	 */
	private FileBuffers()  {
	}

	/**
	 * Returns the text file buffer manager.
	 * 
	 * @return the text file buffer manager
	 */
	public static ITextFileBufferManager getTextFileBufferManager()  {
		return FileBuffersPlugin.getDefault().getFileBufferManager();
	}
	
	/**
	 * Returns the workspace file at the given location or <code>null</code> if
	 * the location is not a valid location in the workspace.
	 * 
	 * @param location the location
	 * @return the workspace file at the location or <code>null</code>
	 */
	public static IFile getWorkspaceFileAtLocation(IPath location) {
		IPath normalized= normalizeLocation(location);
		IWorkspaceRoot workspaceRoot= ResourcesPlugin.getWorkspace().getRoot();
		IFile file= workspaceRoot.getFile(normalized);
		if  (file != null && file.exists())
			return file;
		return null;
	}
	
	/**
	 * Returns a copy of the given location in a normalized form.
	 * 
	 * @param location the location to be normalized
	 * @return normalized copy of location
	 */
	public static IPath normalizeLocation(IPath location) {
		IWorkspaceRoot workspaceRoot= ResourcesPlugin.getWorkspace().getRoot();
		IPath workspacePath= workspaceRoot.getLocation();
		if (!workspacePath.isPrefixOf(location))
			return location.makeAbsolute();
		
		IPath fileLocation= location.removeFirstSegments(workspacePath.segmentCount());
		fileLocation= fileLocation.setDevice(null);
		return fileLocation.makeAbsolute();
		
	}
}
