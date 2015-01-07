// Copyright 2013 Google Inc. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.enterprise.adaptor.fs;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.enterprise.adaptor.AsyncDocIdPusher;
import com.google.enterprise.adaptor.DocId;
import com.google.enterprise.adaptor.DocIdPusher;
import com.google.enterprise.adaptor.fs.WinApi.Kernel32Ex;
import com.google.enterprise.adaptor.fs.WinApi.Netapi32Ex;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.Advapi32;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.LMErr;
import com.sun.jna.platform.win32.W32Errors;
import com.sun.jna.platform.win32.Win32Exception;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.platform.win32.WinNT.FILE_NOTIFY_INFORMATION;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.W32APIOptions;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclFileAttributeView;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Level;
import java.util.logging.Logger;

class WindowsFileDelegate extends NioFileDelegate {
  private static final Logger log
      = Logger.getLogger(WindowsFileDelegate.class.getName());

  private final Advapi32 advapi32;
  private final Kernel32Ex kernel32;
  private final Netapi32Ex netapi32;
  private final WindowsAclFileAttributeViews aclViews;

  private HashMap<Path, MonitorThread>monitors =
      new HashMap<Path, MonitorThread>();

  public WindowsFileDelegate() {
    this(Advapi32.INSTANCE, Kernel32Ex.INSTANCE, Netapi32Ex.INSTANCE,
         new WindowsAclFileAttributeViews());
  }

  @VisibleForTesting
  WindowsFileDelegate(Advapi32 advapi32, Kernel32Ex kernel32,
      Netapi32Ex netapi32, WindowsAclFileAttributeViews aclViews) {
    this.advapi32 = advapi32;
    this.kernel32 = kernel32;
    this.netapi32 = netapi32;
    this.aclViews = aclViews;
  }

  @Override
  public AclFileAttributeViews getAclViews(Path doc) throws IOException {
    return aclViews.getAclViews(doc);
  }

  @Override
  public AclFileAttributeView getShareAclView(Path doc) throws IOException {
    return aclViews.getShareAclView(doc);
  }

  @Override
  public AclFileAttributeView getDfsShareAclView(Path doc) {
    // First check for explicit permissions on the DFS link.
    WinNT.ACL dacl = getDfsExplicitAcl(doc);
    if (dacl == null) {
      // If no explicit permissions, use the permissions from the local 
      // filesystem of the namespace server.
      dacl = getDfsNamespaceAcl(doc.getParent());
    }

    ImmutableList.Builder<AclEntry> builder = ImmutableList.builder();
    for (WinNT.ACCESS_ACEStructure ace : dacl.getACEStructures()) {
      AclEntry entry = aclViews.newAclEntry(ace);
      if (entry != null) {
        builder.add(entry);
      }
    }

    List<AclEntry> acl = builder.build();
    log.log(Level.FINEST, "DFS share ACL for {0}: {1}",
        new Object[] { doc, acl });
    return new SimpleAclFileAttributeView(acl);
  }

  /*
   * Returns the explicit ACL set on a DFS link, or null if no explicit 
   * ACL is set.
   */
  private WinNT.ACL getDfsExplicitAcl(Path doc) throws Win32Exception {
    PointerByReference buf = new PointerByReference();
    int rc = netapi32.NetDfsGetInfo(doc.toString(), null, null, 150, buf);
    if (rc != LMErr.NERR_Success) {
      throw new Win32Exception(rc);
    }
    Netapi32Ex.DFS_INFO_150 info = new Netapi32Ex.DFS_INFO_150(buf.getValue());
    WinNT.ACL dacl;
    if (info.pSecurityDescriptor == null) {
      dacl = null;
    } else {
      // There are explicit permissions set on the DFS link.
      SECURITY_DESCRIPTOR_RELATIVEEx sdr =
          new SECURITY_DESCRIPTOR_RELATIVEEx(info.pSecurityDescriptor);
      dacl = sdr.getDiscretionaryACL();
    }
    rc = netapi32.NetApiBufferFree(buf.getValue());
    if (LMErr.NERR_Success != rc) {
      throw new Win32Exception(rc);
   }
   return dacl;
  }

  /*
   * Returns the ACL set on a DFS namespace. From the Windows dialog box
   * encountered when setting permissions on a DFS link,
   * "By default, permissions are inherited from local file system of the
   * namespace server..."  So if there is no explicit ACL on the link,
   * get the file system ACL from the folder containing the link.
   */
  private WinNT.ACL getDfsNamespaceAcl(Path doc) throws Win32Exception {
    WString wpath = new WString(doc.toString());
    IntByReference lengthNeeded = new IntByReference();
    int daclType = WinNT.DACL_SECURITY_INFORMATION
        | WinNT.PROTECTED_DACL_SECURITY_INFORMATION
        | WinNT.UNPROTECTED_DACL_SECURITY_INFORMATION;

    if (advapi32.GetFileSecurity(wpath, daclType, null, 0, lengthNeeded)) {
      throw new AssertionError("GetFileSecurity was expected to fail with "
          + "ERROR_INSUFFICIENT_BUFFER");
    }

    int rc = kernel32.GetLastError();
    if (rc != W32Errors.ERROR_INSUFFICIENT_BUFFER) {
      throw new Win32Exception(rc);
    }

    Memory memory = new Memory(lengthNeeded.getValue());
    if (!advapi32.GetFileSecurity(wpath, daclType, memory, (int) memory.size(),
        lengthNeeded)) {
      throw new Win32Exception(kernel32.GetLastError());
    }

    SECURITY_DESCRIPTOR_RELATIVEEx securityDescriptor =
        new SECURITY_DESCRIPTOR_RELATIVEEx(memory);
    return securityDescriptor.getDiscretionaryACL();
  }

  public static class ACLEx extends WinNT.ACL {
    private WinNT.ACCESS_ACEStructure[] ACEs;

    public ACLEx(Pointer p) {
      // Don't call super(p), call instead useMemory(p). The reason is
      // that super(p) will parse the security descriptor which is what
      // we're trying to avoid.
      useMemory(p);
      read();
      ACEs = new WinNT.ACCESS_ACEStructure[AceCount];
      int offset = size();
      for (int i = 0; i < AceCount; i++) {
        Pointer share = p.share(offset);
        byte aceType = share.getByte(0);
        WinNT.ACCESS_ACEStructure ace;
        switch (aceType) {
          case WinNT.ACCESS_ALLOWED_ACE_TYPE:
          case WinNT.ACCESS_ALLOWED_OBJECT_ACE_TYPE:
            ace = new WinNT.ACCESS_ALLOWED_ACE(share);
            break;
          case WinNT.ACCESS_DENIED_ACE_TYPE:
          case WinNT.ACCESS_DENIED_OBJECT_ACE_TYPE:
            ace = new WinNT.ACCESS_DENIED_ACE(share);
            break;
          default:
            throw new IllegalArgumentException("Unsupported ACE type "
                + aceType);
        }
        ACEs[i] = ace;
        offset += ace.AceSize;
      }
    }

    public WinNT.ACCESS_ACEStructure[] getACEStructures() {
      return ACEs;
    }
  }

  public static class SECURITY_DESCRIPTOR_RELATIVEEx extends
      WinNT.SECURITY_DESCRIPTOR_RELATIVE {
    private WinNT.ACL DACL;

    public SECURITY_DESCRIPTOR_RELATIVEEx(Pointer p) {
      // Don't call super(p), call instead useMemory(p). The reason is
      // that super(p) will parse the security descriptor which is what
      // we're trying to avoid.
      useMemory(p);
      read();
      if (Dacl != 0) {
        DACL = new ACLEx(getPointer().share(Dacl));
      }
    }

    public WinNT.ACL getDiscretionaryACL() {
      return DACL;
    }
  }

  @Override
  public boolean isDfsNamespace(Path doc) throws IOException {
    // A DFS namespace has a namecount of 0, but so does a shared folder
    // or a filesystem root. This gets called frequently, mostly with paths
    // where namecount is > 0, so avoid getting DFS info in those cases.
    if (doc.getNameCount() > 0) {
      return false;
    }
    Netapi32Ex.DFS_INFO_3 info = getDfsInfo(doc);
    if (info == null) {
      return false;
    }
    return (info.State.intValue() & Netapi32Ex.DFS_ROOT_FLAVOR_MASK) != 0;
  }

  @Override
  public boolean isDfsLink(Path doc) throws IOException {
    // A DFS link has a namecount of at least 1, but so does a anything at
    // the top level of a shared folder or a filesystem root.
    Netapi32Ex.DFS_INFO_3 info = getDfsInfo(doc);
    if (info == null) {
      return false;
    }
    return (info.State.intValue() & Netapi32Ex.DFS_ROOT_FLAVOR_MASK) == 0;
  }

  @Override
  public Path resolveDfsLink(Path doc) throws IOException {
    Netapi32Ex.DFS_INFO_3 info = getDfsInfo(doc);
    if (info == null ||
        (info.State.intValue() & Netapi32Ex.DFS_ROOT_FLAVOR_MASK) != 0) {
      return null;
    }

    // Find the active storage.
    String storageUnc = null;
    for (int i = 0; i < info.StorageInfos.length; i++) {
      Netapi32Ex.DFS_STORAGE_INFO storeInfo = info.StorageInfos[i];
      if (storeInfo.State.intValue() == Netapi32Ex.DFS_STORAGE_STATE_ONLINE) {
        storageUnc = String.format("\\\\%s\\%s", storeInfo.ServerName,
            storeInfo.ShareName);
        break;
      }
    }
    if (storageUnc == null) {
      throw new IOException("The DFS path " + doc
          + " does not have an active storage.");
    }

    return Paths.get(storageUnc);
  }

  private Netapi32Ex.DFS_INFO_3 getDfsInfo(Path doc) throws IOException {
    PointerByReference buf = new PointerByReference();
    int rc = netapi32.NetDfsGetInfo(doc.toString(), null, null, 3, buf);
    if (rc != LMErr.NERR_Success) {
      return null;
    }

    Netapi32Ex.DFS_INFO_3 info = new Netapi32Ex.DFS_INFO_3(buf.getValue());
    netapi32.NetApiBufferFree(buf.getValue());
    return info;
  }

  @Override
  public List<Path> enumerateDfsLinks(Path doc) throws IOException {
    PointerByReference buf = new PointerByReference();
    IntByReference bufSize = new IntByReference();

    int rc = netapi32.NetDfsEnum(doc.toString(), 1, -1, buf, bufSize, null);
    if (rc != LMErr.NERR_Success) {
      throw new IOException("Unable to enumerate DFS links for " + doc
          + ". Code: " + rc);
    }
    
    int numLinks = bufSize.getValue();
    ImmutableList.Builder<Path> builder = ImmutableList.builder();
    try {
      Pointer bufp = buf.getValue();
      for (int i = 0; i < numLinks; i++) {
        Netapi32Ex.DFS_INFO_1 info = new Netapi32Ex.DFS_INFO_1(bufp);
        Path path = Paths.get(info.EntryPath.toString()); 
        // For some reason, NetDfsEnum includes the namespace itself in the
        // enumeration. The namespace has a nameCount of 0, the links have a
        // nameCount > 0.
        if (path.getNameCount() > 0) {
          builder.add(preserveOriginalNamespace(doc, path));
        }
        bufp = bufp.share(info.size());
      }
      return builder.build();
    } finally {
      netapi32.NetApiBufferFree(buf.getValue());
    }
  }

  /*
   * Enumerated DFS links tend to have normalized server names
   * in the path, either all uppercase, or FQDN, or both.
   * This re-resolves the link against our supplied Namespace.
   */
  @VisibleForTesting
  static Path preserveOriginalNamespace(Path namespace, Path link) {
    return namespace.getRoot().resolve(link.getRoot().relativize(link));
  }

  @Override
  public DocId newDocId(Path doc) throws IOException {
    File file = doc.toFile().getCanonicalFile();
    String id = file.getAbsolutePath().replace('\\', '/');
    if (file.isDirectory() && !id.endsWith("/")) {
      id += "/";
    }
    if (id.startsWith("//")) {
      // String.replaceFirst uses regular expression string and replacement
      // so they need to be escaped appropriately. The above String.replace
      // does NOT use expressions so regex escaping is not needed.
      id = id.replaceFirst("//", "\\\\\\\\");
    }
    // Windows has a maximum pathname length of 260 characters. This limit
    // can be worked around with some effort.  For details see:
    // http://msdn.microsoft.com/library/windows/desktop/aa365247.aspx
    if (id.length() < WinNT.MAX_PATH) {
      return new DocId(id);
    } else {
      throw new IllegalArgumentException("the path is too long");
    }
  }

  @Override
  public void startMonitorPath(Path watchPath, AsyncDocIdPusher pusher)
      throws IOException {

    if (!Files.isDirectory(watchPath, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("Could not monitor " + watchPath
          + ". The path is not a valid directory.");
    }

    CountDownLatch startSignal;
    synchronized (monitors) {
      log.log(Level.FINE, "Considering monitor for {0}", watchPath );
      MonitorThread monitorThread = monitors.get(watchPath);
      if (monitorThread != null) {
        log.log(Level.FINE, "Already monitoring {0}", watchPath);
        return;
      }
      startSignal = new CountDownLatch(1);
      monitorThread = new MonitorThread(watchPath, pusher, startSignal);
      monitorThread.start();
      monitors.put(watchPath, monitorThread);
      log.log(Level.FINE, "Number of monitors {0}", monitors.size());
    }
    try {
      log.log(Level.FINE, "Waiting for monitor start signal {0}", watchPath);
      startSignal.await();
      log.log(Level.FINE, "Received monitor start signal {0}", watchPath);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private void stopMonitorPaths() {
    synchronized (monitors) {
      for (MonitorThread monitorThread : monitors.values()) {
        log.log(Level.FINE, "Asking for shutdown {0}", monitorThread.watchPath);
        monitorThread.shutdown();
        log.log(Level.FINE, "After shutdown {0}", monitorThread.watchPath);
      }
      monitors.clear();
    }
  }

  private class MonitorThread extends Thread {
    private final Path watchPath;
    private final AsyncDocIdPusher pusher;
    private final CountDownLatch startSignal;
    private final HANDLE stopEvent;

    public MonitorThread(Path watchPath, AsyncDocIdPusher pusher,
        CountDownLatch startSignal) {
      Preconditions.checkNotNull(watchPath, "the watchPath may not be null");
      Preconditions.checkNotNull(pusher, "the pusher may not be null");
      Preconditions.checkNotNull(startSignal,
                                 "the start signal may not be null");
      this.watchPath = watchPath;
      this.pusher = pusher;
      this.startSignal = startSignal;
      stopEvent = Kernel32.INSTANCE.CreateEvent(null, false, false, null);
    }

    public void shutdown() {
      kernel32.SetEvent(stopEvent);
      boolean interrupt = false;
      while (true) {
        try {
          log.log(Level.FINE, "Waiting for monitor to join {0}", watchPath);
          join();
          log.log(Level.FINE, "Monitor joined {0}", watchPath);
          break;
        } catch (InterruptedException ex) {
          interrupt = true;
        }
      }
      if (interrupt) {
        Thread.currentThread().interrupt();
      }
      kernel32.CloseHandle(stopEvent);
    }

    public void run() {
      log.entering("WindowsFileDelegate", "MonitorThread.run", watchPath);
      try {
        runMonitorLoop();
      } catch (IOException e) {
        log.log(Level.WARNING, "Unable to monitor " + watchPath, e);
      } finally {
        // Wake up caller, in case monitor fails to start up.
        startSignal.countDown();
      }
      log.exiting("WindowsFileDelegate", "MonitorThread.run", watchPath);
    }

    private void runMonitorLoop() throws IOException {
      int mask = Kernel32.FILE_SHARE_READ | Kernel32.FILE_SHARE_WRITE
          | Kernel32.FILE_SHARE_DELETE;
      HANDLE handle = kernel32.CreateFile(watchPath.toString(),
          Kernel32.FILE_LIST_DIRECTORY, mask, null, Kernel32.OPEN_EXISTING,
          Kernel32.FILE_FLAG_BACKUP_SEMANTICS | Kernel32.FILE_FLAG_OVERLAPPED,
          null);
      if (Kernel32.INVALID_HANDLE_VALUE.equals(handle)) {
        throw new IOException("Unable to open " + watchPath
            + ". GetLastError: " + kernel32.GetLastError());
      }
      try {
        runMonitorLoop(handle);
      } finally {
        kernel32.CloseHandle(handle);
      }
    }

  /**
   * Runs a loop that monitors for file change events or a stop event.
   *
   * @param handle The handle to read changs from
   * @throws IOException on error
   */
    private void runMonitorLoop(HANDLE handle) throws IOException {
      Kernel32.OVERLAPPED ol = new Kernel32.OVERLAPPED();

      final FILE_NOTIFY_INFORMATION info = new FILE_NOTIFY_INFORMATION(4096);
      int notifyFilter = Kernel32.FILE_NOTIFY_CHANGE_SECURITY
          | Kernel32.FILE_NOTIFY_CHANGE_CREATION
          | Kernel32.FILE_NOTIFY_CHANGE_LAST_WRITE
          | Kernel32.FILE_NOTIFY_CHANGE_ATTRIBUTES
          | Kernel32.FILE_NOTIFY_CHANGE_DIR_NAME
          | Kernel32.FILE_NOTIFY_CHANGE_FILE_NAME;

      Kernel32.OVERLAPPED_COMPLETION_ROUTINE changesCallback =
          new Kernel32.OVERLAPPED_COMPLETION_ROUTINE() {
            public void callback(int errorCode, int nBytesTransferred,
                Kernel32.OVERLAPPED ol) {
              log.entering("WindowsFileDelegate", "changesCallback",
                  new Object[] { errorCode, nBytesTransferred });
              if (errorCode == W32Errors.ERROR_SUCCESS) {
                try {
                  handleChanges(info);
                } catch (IOException e) {
                  log.log(Level.WARNING,
                      "Error processing file change notifications.", e);
                }
              } else if (errorCode == W32Errors.ERROR_NOTIFY_ENUM_DIR) {
                // An error of ERROR_NOTIFY_ENUM_DIR means that there was
                // a notification buffer overflows which can cause some 
                // notifications to be lost.
                log.log(Level.INFO,
                    "There was a buffer overflow during file monitoring. "
                    + "Some file update notifications may have been lost.");
              } else {
                log.log(Level.WARNING,
                    "Unable to read data notification data. errorCode: {0}",
                    errorCode);
              }
              log.exiting("WindowsFileDelegate", "changesCallback");
            }
          };

      while (true) {
        if (!kernel32.ReadDirectoryChangesW(handle, info, info.size(),
            true, notifyFilter, null, ol, changesCallback)) {
          throw new IOException("Unable to open " + watchPath
              + ". GetLastError: " + kernel32.GetLastError());
        }

        // Signal any waiting threads that the monitor is now active.
        startSignal.countDown();

        log.log(Level.FINER, "Waiting for notifications.");
        int waitResult = kernel32.WaitForSingleObjectEx(stopEvent,
            Kernel32.INFINITE, true);
        log.log(Level.FINER, "Got notification. waitResult: {0}", waitResult);

        if (waitResult == Kernel32Ex.WAIT_IO_COMPLETION) {
          log.log(Level.FINEST,
              "WaitForSingleObjectEx returned WAIT_IO_COMPLETION. "
              + "A notification was sent to the monitor callback.");
          continue;
        } else if (waitResult == WinBase.WAIT_OBJECT_0) {
          log.log(Level.FINE,
              "Terminate event has been set, ending file monitor.");
          return;
        } else {
          throw new IOException(
              "Unexpected result from WaitForSingleObjectEx: " + waitResult
              + ". GetLastError: " + kernel32.GetLastError());
        }
      }
    }

    private void handleChanges(FILE_NOTIFY_INFORMATION info)
        throws IOException {
      info.read();
      do {
        Path changePath = watchPath.resolve(info.getFilename());
        switch (info.Action) {
          case Kernel32.FILE_ACTION_MODIFIED:
            log.log(Level.FINEST, "Modified: {0}", changePath);
            pushPath(changePath);
            break;
          case Kernel32.FILE_ACTION_ADDED:
          case Kernel32.FILE_ACTION_RENAMED_NEW_NAME:
            log.log(Level.FINEST, "Added: {0}", changePath);
            pushPath(changePath.getParent());
            break;
          case Kernel32.FILE_ACTION_REMOVED:
          case Kernel32.FILE_ACTION_RENAMED_OLD_NAME:
            log.log(Level.FINEST, "Removed: {0}", changePath);
            pushPath(changePath);
            pushPath(changePath.getParent());
            break;
          default:
            // Nothing to do here.
            break;
        }
        info = info.next();
      } while (info != null);
    }

    private void pushPath(Path doc) {
      try {
        DocId docid;
        try {
          docid = newDocId(doc);
        } catch (IllegalArgumentException e) {
          log.log(Level.WARNING, "Skipping {0} because {1}.",
                  new Object[] { doc, e.getMessage() });
          return;
        }
        // For deleted, moved or renamed files we want to push the old name
        // so in this case, feed it if the path does not exists.
        boolean deletedOrMoved = !Files.exists(doc);
        if (deletedOrMoved || isRegularFile(doc) || isDirectory(doc)) {
          log.log(Level.FINE, "Pushing docid {0}", docid);
          pusher.pushRecord(new DocIdPusher.Record.Builder(docid)
              .setCrawlImmediately(true).build());
        } else {
          log.log(Level.INFO,
              "Skipping {0}. It is not a regular file or directory.", doc);
        }
      } catch (IOException e) {
        log.log(Level.WARNING, "Unable to push the path " + doc
            + " to the GSA.", e);
      }
    }
  }

  @Override
  public void destroy() {
    stopMonitorPaths();
  }
}
