package com.intellij.javascript.karma.server;

import com.google.common.collect.Lists;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.*;
import com.intellij.javascript.karma.KarmaConfig;
import com.intellij.javascript.karma.coverage.KarmaCoverageSession;
import com.intellij.javascript.karma.util.GsonUtil;
import com.intellij.javascript.karma.util.ProcessOutputArchive;
import com.intellij.javascript.karma.util.StreamEventListener;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.CharsetToolkit;
import com.intellij.util.ArrayUtil;
import com.intellij.util.ObjectUtils;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @author Sergey Simonchik
 */
public class KarmaServer {

  private static final Logger LOG = Logger.getInstance(KarmaServer.class);

  private final File myConfigurationFile;
  private final KillableColoredProcessHandler myProcessHandler;
  private final ProcessOutputArchive myProcessOutputArchive;
  private final KarmaJsSourcesLocator myKarmaJsSourcesLocator;
  private final KarmaServerState myState;

  // accessed in EDT only
  private final AtomicBoolean myOnReadyFired = new AtomicBoolean(false);
  private boolean myOnReadyExecuted = false;
  private Integer myExitCode = null;
  private final List<KarmaServerReadyListener> myDoListWhenReady = Lists.newCopyOnWriteArrayList();
  private final List<KarmaServerTerminatedListener> myDoListWhenTerminated = Lists.newCopyOnWriteArrayList();
  private final List<Runnable> myDoListWhenReadyWithCapturedBrowser = Lists.newCopyOnWriteArrayList();

  private volatile KarmaCoverageSession myActiveCoverageSession;
  private final Map<String, StreamEventHandler> myHandlers = ContainerUtil.newConcurrentMap();
  private final File myCoverageTempDir;

  private volatile KarmaConfig myKarmaConfig;

  public KarmaServer(@NotNull File nodeInterpreter,
                     @NotNull File karmaPackageDir,
                     @NotNull File configurationFile) throws IOException {
    myCoverageTempDir = FileUtil.createTempDirectory("karma-intellij-coverage-", null);
    /* 'nodeInterpreter', 'karmaPackageDir' and 'configurationFile'
        are already checked in KarmaRunConfiguration.checkConfiguration */
    myConfigurationFile = configurationFile;
    myKarmaJsSourcesLocator = new KarmaJsSourcesLocator(karmaPackageDir);
    myState = new KarmaServerState(this);
    try {
      myProcessHandler = startServer(nodeInterpreter, configurationFile);
    }
    catch (ExecutionException e) {
      throw new IOException("Can not create karma server process", e);
    }
    myProcessOutputArchive = new ProcessOutputArchive(myProcessHandler);
    registerStreamEventHandler(new StreamEventHandler() {
      @NotNull
      @Override
      public String getEventType() {
        return "coverageFinished";
      }

      @Override
      public void handle(@NotNull JsonElement eventBody) {
        KarmaCoverageSession coverageSession = myActiveCoverageSession;
        myActiveCoverageSession = null;
        if (coverageSession != null) {
          String path = GsonUtil.asString(eventBody);
          coverageSession.onCoverageSessionFinished(new File(path));
        }
      }
    });
    registerStreamEventHandler(new StreamEventHandler() {
      @NotNull
      @Override
      public String getEventType() {
        return "configFile";
      }

      @Override
      public void handle(@NotNull JsonElement eventBody) {
        myKarmaConfig = KarmaConfig.parseFromJson(eventBody);
      }
    });

    myProcessOutputArchive.addStreamEventListener(new StreamEventListener() {
      @Override
      public void on(@NotNull String eventType, @NotNull String eventBody) {
        JsonElement jsonElement;
        try {
          JsonParser jsonParser = new JsonParser();
          jsonElement = jsonParser.parse(eventBody);
        }
        catch (Exception e) {
          LOG.warn("Cannot parse message from karma server:" +
                   " (eventType: " + eventType + ", eventBody: " + eventBody + ")");
          return;
        }
        StreamEventHandler handler = myHandlers.get(eventType);
        if (handler != null) {
          handler.handle(jsonElement);
        }
        else {
          LOG.warn("Cannot find handler for " + eventType);
        }
      }
    });
    myProcessOutputArchive.startNotify();
    Disposer.register(ApplicationManager.getApplication(), new Disposable() {
      @Override
      public void dispose() {
        if (!myProcessHandler.isProcessTerminated()) {
          ScriptRunnerUtil.terminateProcessHandler(myProcessHandler, 500, null);
        }
      }
    });
  }

  @NotNull
  public File getConfigurationFile() {
    return myConfigurationFile;
  }

  @NotNull
  public KarmaJsSourcesLocator getKarmaJsSourcesLocator() {
    return myKarmaJsSourcesLocator;
  }

  void registerStreamEventHandler(@NotNull StreamEventHandler handler) {
    myHandlers.put(handler.getEventType(), handler);
  }

  private KillableColoredProcessHandler startServer(@NotNull File nodeInterpreter,
                                                    @NotNull File configurationFile) throws IOException, ExecutionException {
    GeneralCommandLine commandLine = new GeneralCommandLine();
    commandLine.setPassParentEnvironment(true);
    commandLine.setWorkDirectory(configurationFile.getParentFile());
    commandLine.setExePath(nodeInterpreter.getAbsolutePath());
    File serverFile = myKarmaJsSourcesLocator.getServerAppFile();
    commandLine.addParameter(serverFile.getAbsolutePath());
    commandLine.addParameter("--karmaPackageDir=" + myKarmaJsSourcesLocator.getKarmaPackageDir().getAbsolutePath());
    commandLine.addParameter("--configFile=" + configurationFile.getAbsolutePath());
    commandLine.addParameter("--coverageTempDir=" + myCoverageTempDir.getAbsolutePath());

    LOG.info("Starting karma server: " + commandLine.getCommandLineString());
    final Process process = commandLine.createProcess();
    KillableColoredProcessHandler processHandler = new KillableColoredProcessHandler(
      process,
      commandLine.getCommandLineString(),
      CharsetToolkit.UTF8_CHARSET
    );

    processHandler.addProcessListener(new ProcessAdapter() {
      @Override
      public void processTerminated(final ProcessEvent event) {
        FileUtil.asyncDelete(myCoverageTempDir);
        KarmaServerRegistry.serverTerminated(KarmaServer.this);
        UIUtil.invokeLaterIfNeeded(new Runnable() {
          @Override
          public void run() {
            myDoListWhenReady.clear();
            myDoListWhenReadyWithCapturedBrowser.clear();
            fireOnTerminated(event.getExitCode());
          }
        });
      }
    });
    processHandler.addProcessListener(myState);
    ProcessTerminatedListener.attach(processHandler);
    processHandler.setShouldDestroyProcessRecursively(true);
    return processHandler;
  }

  @NotNull
  public ProcessOutputArchive getProcessOutputArchive() {
    return myProcessOutputArchive;
  }

  void fireOnReady(final int webServerPort) {
    if (myOnReadyFired.compareAndSet(false, true)) {
      UIUtil.invokeLaterIfNeeded(new Runnable() {
        @Override
        public void run() {
          myOnReadyExecuted = true;
          List<KarmaServerReadyListener> listeners = ContainerUtil.newArrayList(myDoListWhenReady);
          myDoListWhenReady.clear();
          for (KarmaServerReadyListener listener : listeners) {
            listener.onReady(webServerPort);
          }
          processWhenReadyWithCapturedBrowserQueue();
        }
      });
    }
  }

  private void fireOnTerminated(final int exitCode) {
    myExitCode = exitCode;
    for (KarmaServerTerminatedListener listener : myDoListWhenTerminated) {
      listener.onTerminated(exitCode);
    }
  }

  /**
   * Executes {@code} task in EDT when the server is ready
   */
  public void doWhenReady(@NotNull final KarmaServerReadyListener readyCallback) {
    UIUtil.invokeLaterIfNeeded(new Runnable() {
      @Override
      public void run() {
        if (myOnReadyExecuted) {
          readyCallback.onReady(myState.getServerPort());
        }
        else {
          myDoListWhenReady.add(readyCallback);
        }
      }
    });
  }

  /**
   * Executes {@code} task in EDT when the server is ready
   */
  public void doWhenTerminated(@NotNull final KarmaServerTerminatedListener terminationCallback) {
    UIUtil.invokeLaterIfNeeded(new Runnable() {
      @Override
      public void run() {
        if (myExitCode != null) {
          terminationCallback.onTerminated(myExitCode);
        }
        else {
          myDoListWhenTerminated.add(terminationCallback);
        }
      }
    });
  }

  /**
   * Executes {@code} task in EDT when the server is ready and has at least one captured browser.
   */
  public void doWhenReadyWithCapturedBrowser(@NotNull final Runnable task) {
    UIUtil.invokeLaterIfNeeded(new Runnable() {
      @Override
      public void run() {
        if (myOnReadyFired.get() && hasCapturedBrowsers()) {
          task.run();
        }
        else {
          myDoListWhenReadyWithCapturedBrowser.add(task);
        }
      }
    });
  }

  public boolean isReady() {
    return myOnReadyFired.get();
  }

  @Nullable
  public KarmaConfig getKarmaConfig() {
    return myKarmaConfig;
  }

  public int getServerPort() {
    return myState.getServerPort();
  }

  public boolean hasCapturedBrowsers() {
    return myState.hasCapturedBrowser();
  }

  void onBrowserCaptured() {
    UIUtil.invokeLaterIfNeeded(new Runnable() {
      @Override
      public void run() {
        processWhenReadyWithCapturedBrowserQueue();
      }
    });
  }

  private void processWhenReadyWithCapturedBrowserQueue() {
    if (myDoListWhenReadyWithCapturedBrowser.isEmpty()) {
      return;
    }
    if (myOnReadyFired.get() && hasCapturedBrowsers()) {
      List<Runnable> tasks = ContainerUtil.newArrayList(myDoListWhenReadyWithCapturedBrowser);
      myDoListWhenReadyWithCapturedBrowser.clear();
      for (Runnable task : tasks) {
        task.run();
      }
    }
  }

  @NotNull
  public Collection<String> getCapturedBrowsers() {
    return myState.getCapturedBrowsers();
  }

  public void startCoverageSession(@NotNull KarmaCoverageSession coverageSession) {
    // clear directory
    if (myCoverageTempDir.isDirectory()) {
      File[] children = ObjectUtils.notNull(myCoverageTempDir.listFiles(), ArrayUtil.EMPTY_FILE_ARRAY);
      for (File child : children) {
        FileUtil.delete(child);
      }
    }
    else {
      FileUtil.createDirectory(myCoverageTempDir);
    }
    myActiveCoverageSession = coverageSession;
  }

}
