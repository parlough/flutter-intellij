/*
 * Copyright 2016 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */
package io.flutter.run;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.Executor;
import com.intellij.execution.configurations.CommandLineState;
import com.intellij.execution.configurations.CommandLineTokenizer;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.configurations.GeneralCommandLine.ParentEnvironmentType;
import com.intellij.execution.configurations.RuntimeConfigurationError;
import com.intellij.execution.executors.DefaultDebugExecutor;
import com.intellij.execution.filters.TextConsoleBuilder;
import com.intellij.execution.filters.TextConsoleBuilderImpl;
import com.intellij.execution.filters.UrlFilter;
import com.intellij.execution.process.ColoredProcessHandler;
import com.intellij.execution.process.OSProcessHandler;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.process.ProcessTerminatedListener;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.ui.ConsoleView;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.Separator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.CharsetToolkit;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.net.NetUtils;
import com.jetbrains.lang.dart.ide.runner.DartConsoleFilter;
import com.jetbrains.lang.dart.ide.runner.DartRelativePathsConsoleFilter;
import com.jetbrains.lang.dart.ide.runner.client.DartiumUtil;
import com.jetbrains.lang.dart.ide.runner.server.OpenDartObservatoryUrlAction;
import com.jetbrains.lang.dart.sdk.DartSdk;
import com.jetbrains.lang.dart.sdk.DartSdkUtil;
import io.flutter.FlutterBundle;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.StringTokenizer;

public abstract class FlutterAppStateBase extends CommandLineState {
  protected final @NotNull FlutterRunnerParameters myRunnerParameters;
  private int myObservatoryPort = -1;

  public FlutterAppStateBase(final @NotNull ExecutionEnvironment env) throws ExecutionException {
    super(env);
    myRunnerParameters = ((FlutterRunConfiguration)env.getRunProfile()).getRunnerParameters().clone();

    final Project project = env.getProject();
    try {
      myRunnerParameters.check(project);
    }
    catch (RuntimeConfigurationError e) {
      throw new ExecutionException(e);
    }

    final TextConsoleBuilder builder = getConsoleBuilder();
    if (builder instanceof TextConsoleBuilderImpl) {
      ((TextConsoleBuilderImpl)builder).setUsePredefinedMessageFilter(false);
    }

    try {
      builder.addFilter(new DartConsoleFilter(project, myRunnerParameters.getDartFileOrDirectory()));
      builder.addFilter(new DartRelativePathsConsoleFilter(project, myRunnerParameters.computeProcessWorkingDirectory(project)));
      builder.addFilter(new UrlFilter());
    }
    catch (RuntimeConfigurationError e) { /* can't happen because already checked */}
  }

  @Override
  protected AnAction[] createActions(final ConsoleView console, final ProcessHandler processHandler, final Executor executor) {
    // These actions are effectively added only to the Run tool window. For Debug see DartCommandLineDebugProcess.registerAdditionalActions()
    final List<AnAction> actions = new ArrayList(Arrays.asList(super.createActions(console, processHandler, executor)));
    addObservatoryActions(actions, processHandler);
    return actions.toArray(new AnAction[actions.size()]);
  }

  protected void addObservatoryActions(List<AnAction> actions, final ProcessHandler processHandler) {
    actions.add(new Separator());
    actions.add(new OpenDartObservatoryUrlAction(
      "http://" + NetUtils.getLocalHostString() + ":" + myObservatoryPort,
      () -> !processHandler.isProcessTerminated()));
  }

  @NotNull
  @Override
  protected ProcessHandler startProcess() throws ExecutionException {
    return doStartProcess(null);
  }

  protected ProcessHandler doStartProcess(final @Nullable String overriddenMainFilePath) throws ExecutionException {
    final GeneralCommandLine commandLine = createCommandLine(overriddenMainFilePath);
    final OSProcessHandler processHandler = new ColoredProcessHandler(commandLine);

    ProcessTerminatedListener.attach(processHandler, getEnvironment().getProject());
    return processHandler;
  }

  private GeneralCommandLine createCommandLine(@Nullable final String overriddenMainFilePath) throws ExecutionException {
    final DartSdk sdk = DartSdk.getDartSdk(getEnvironment().getProject());
    if (sdk == null) {
      throw new ExecutionException(FlutterBundle.message("dart.sdk.is.not.configured"));
    }

    final GeneralCommandLine commandLine = new GeneralCommandLine()
      .withWorkDirectory(myRunnerParameters.computeProcessWorkingDirectory(getEnvironment().getProject()));
    commandLine.setCharset(CharsetToolkit.UTF8_CHARSET);
    commandLine.setExePath(FileUtil.toSystemDependentName(DartSdkUtil.getDartExePath(sdk)));
    commandLine.getEnvironment().putAll(myRunnerParameters.getEnvs());
    commandLine
      .withParentEnvironmentType(myRunnerParameters.isIncludeParentEnvs() ? ParentEnvironmentType.CONSOLE : ParentEnvironmentType.NONE);
    setupParameters(sdk, commandLine, overriddenMainFilePath);

    return commandLine;
  }

  private void setupParameters(@NotNull final DartSdk sdk,
                               @NotNull final GeneralCommandLine commandLine,
                               @Nullable final String overriddenMainFilePath) throws ExecutionException {
    int customObservatoryPort = -1;

    final String vmOptions = myRunnerParameters.getVMOptions();
    if (vmOptions != null) {
      final StringTokenizer vmOptionsTokenizer = new CommandLineTokenizer(vmOptions);
      while (vmOptionsTokenizer.hasMoreTokens()) {
        final String vmOption = vmOptionsTokenizer.nextToken();
        commandLine.addParameter(vmOption);

        try {
          if (vmOption.equals("--enable-vm-service") || vmOption.equals("--observe")) {
            customObservatoryPort = 8181; // default port, see https://www.dartlang.org/tools/dart-vm/
          }
          else if (vmOption.startsWith("--enable-vm-service:")) {
            customObservatoryPort = parseIntBeforeSlash(vmOption.substring("--enable-vm-service:".length()));
          }
          else if (vmOption.startsWith("--observe:")) {
            customObservatoryPort = parseIntBeforeSlash(vmOption.substring("--observe:".length()));
          }
        }
        catch (NumberFormatException ignore) {/**/}
      }
    }

    if (myRunnerParameters.isCheckedMode()) {
      commandLine.addParameter(DartiumUtil.CHECKED_MODE_OPTION);
    }

    final VirtualFile dartFile;
    try {
      dartFile = myRunnerParameters.getDartFileOrDirectory();
    }
    catch (RuntimeConfigurationError e) {
      throw new ExecutionException(e);
    }

    if (DefaultDebugExecutor.EXECUTOR_ID.equals(getEnvironment().getExecutor().getId())) {
      commandLine.addParameter("--pause_isolates_on_start");
    }

    if (customObservatoryPort > 0) {
      myObservatoryPort = customObservatoryPort;
    }
    else {
      try {
        myObservatoryPort = NetUtils.findAvailableSocketPort();
      }
      catch (IOException e) {
        throw new ExecutionException(e);
      }

      commandLine.addParameter("--enable-vm-service:" + myObservatoryPort);
    }

    commandLine.addParameter(FileUtil.toSystemDependentName(overriddenMainFilePath == null ? dartFile.getPath() : overriddenMainFilePath));

    final String arguments = myRunnerParameters.getArguments();
    if (arguments != null) {
      final StringTokenizer argumentsTokenizer = new CommandLineTokenizer(arguments);
      while (argumentsTokenizer.hasMoreTokens()) {
        commandLine.addParameter(argumentsTokenizer.nextToken());
      }
    }
  }

  private static int parseIntBeforeSlash(@NotNull final String s) throws NumberFormatException {
    // "5858" or "5858/0.0.0.0"
    final int index = s.indexOf('/');
    return Integer.parseInt(index > 0 ? s.substring(0, index) : s);
  }

  public int getObservatoryPort() {
    return myObservatoryPort;
  }
}
