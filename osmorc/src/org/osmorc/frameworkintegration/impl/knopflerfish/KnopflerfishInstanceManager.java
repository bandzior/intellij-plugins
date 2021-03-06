/*
 * Copyright (c) 2007-2009, Osmorc Development Team
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 *     * Redistributions of source code must retain the above copyright notice, this list
 *       of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above copyright notice, this
 *       list of conditions and the following disclaimer in the documentation and/or other
 *       materials provided with the distribution.
 *     * Neither the name of 'Osmorc Development Team' nor the names of its contributors may be
 *       used to endorse or promote products derived from this software without specific
 *       prior written permission.
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY
 * EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL
 * THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT
 * OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 * HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR
 * TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE,
 * EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package org.osmorc.frameworkintegration.impl.knopflerfish;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.io.JarUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.annotations.NotNull;
import org.osmorc.frameworkintegration.AbstractFrameworkInstanceManager;
import org.osmorc.frameworkintegration.FrameworkInstanceDefinition;
import org.osmorc.frameworkintegration.FrameworkLibraryCollector;
import org.osmorc.run.ui.SelectedBundle;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;

/**
 * @author Robert F. Beeger (robert@beeger.net)
 */
public class KnopflerfishInstanceManager extends AbstractFrameworkInstanceManager {
  private static final Logger LOG = Logger.getInstance(KnopflerfishInstanceManager.class);

  private static final String[] BUNDLE_DIRS = {"knopflerfish.org/osgi", "knopflerfish.org/osgi/jars/*", "osgi", "osgi/jars/*"};
  private static final Pattern SYSTEM_BUNDLE = Pattern.compile("framework.*\\.jar");
  private static final Pattern SHELL_BUNDLES = Pattern.compile("(log_api|cm_api|console_all|consoletty|frameworkcommands).*\\.jar");

  @Override
  public void collectLibraries(@NotNull final FrameworkInstanceDefinition frameworkInstanceDefinition,
                               @NotNull final FrameworkLibraryCollector collector) {
    VirtualFile installFolder = LocalFileSystem.getInstance().findFileByPath(frameworkInstanceDefinition.getBaseFolder());
    if (installFolder == null || !installFolder.isDirectory()) {
      LOG.warn(frameworkInstanceDefinition.getBaseFolder() + " is not a folder");
      return;
    }

    VirtualFile kf2Folder = installFolder.findChild("knopflerfish.org");
    final VirtualFile osgiFolder = kf2Folder != null ? kf2Folder.findChild("osgi") : installFolder.findChild("osgi");
    if (osgiFolder == null) {
      LOG.warn(installFolder.getPath() + " does not contain neither 'osgi' nor 'knopflerfish.org/osgi'");
      return;
    }

    ApplicationManager.getApplication().runWriteAction(new Runnable() {
      public void run() {
        osgiFolder.refresh(false, true);

        List<VirtualFile> directoriesToAdd = new ArrayList<VirtualFile>();
        VirtualFile jarsFolder = osgiFolder.findChild("jars");
        if (jarsFolder != null) {
          if (!jarsFolder.isDirectory()) {
            LOG.warn(jarsFolder.getPath() + " is not a folder");
            return;
          }
          VirtualFile[] files = jarsFolder.getChildren();
          for (VirtualFile file : files) {
            if (file.isDirectory()) {
              directoriesToAdd.add(file);
            }
          }
        }

        collector.collectFrameworkLibraries(new KnopflerfishSourceFinder(osgiFolder), directoriesToAdd);
      }
    });
  }

  @NotNull
  @Override
  public Collection<SelectedBundle> getFrameworkBundles(@NotNull FrameworkInstanceDefinition instance, @NotNull FrameworkBundleType type) {
    Collection<SelectedBundle> bundles = super.getFrameworkBundles(instance, type);
    if (type == FrameworkBundleType.SHELL && bundles.size() < 5) {
      return ContainerUtil.emptyList();
    }
    return bundles;
  }

  @NotNull
  @Override
  protected String[] getBundleDirectories() {
    return BUNDLE_DIRS;
  }

  @NotNull
  @Override
  protected Result checkType(@NotNull File file, @NotNull FrameworkBundleType type) {
    if (type == FrameworkBundleType.SYSTEM) {
      return Result.isA(SYSTEM_BUNDLE.matcher(file.getName()).matches() && JarUtil.containsClass(file, KnopflerfishRunner.MAIN_CLASS));
    }
    else if (type == FrameworkBundleType.SHELL) {
      return Result.oneOf(SHELL_BUNDLES.matcher(file.getName()).matches());
    }

    return super.checkType(file, type);
  }
}
