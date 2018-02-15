// Copyright 2017 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.rules.cpp;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.rules.cpp.CcCommon.CoptsFilter;
import com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.FeatureConfiguration;
import com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.Variables;
import com.google.devtools.build.lib.rules.cpp.CppCompileAction.DotdFile;
import com.google.devtools.build.lib.skyframe.serialization.ObjectCodec;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec.VisibleForSerialization;
import com.google.devtools.build.lib.util.Pair;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/** The compile command line for the C++ compile action. */
@AutoCodec
public final class CompileCommandLine {
  public static final ObjectCodec<CompileCommandLine> CODEC = new CompileCommandLine_AutoCodec();

  private final Artifact sourceFile;
  private final CoptsFilter coptsFilter;
  private final FeatureConfiguration featureConfiguration;
  private final PathFragment crosstoolTopPathFragment;
  private final CcToolchainFeatures.Variables variables;
  private final String actionName;
  private final DotdFile dotdFile;

  @AutoCodec.Instantiator
  @VisibleForSerialization
  CompileCommandLine(
      Artifact sourceFile,
      CoptsFilter coptsFilter,
      FeatureConfiguration featureConfiguration,
      PathFragment crosstoolTopPathFragment,
      CcToolchainFeatures.Variables variables,
      String actionName,
      DotdFile dotdFile) {
    this.sourceFile = Preconditions.checkNotNull(sourceFile);
    this.coptsFilter = coptsFilter;
    this.featureConfiguration = Preconditions.checkNotNull(featureConfiguration);
    this.crosstoolTopPathFragment = crosstoolTopPathFragment;
    this.variables = variables;
    this.actionName = actionName;
    this.dotdFile = isGenerateDotdFile(sourceFile) ? dotdFile : null;
  }

  /** Returns true if Dotd file should be generated. */
  private boolean isGenerateDotdFile(Artifact sourceArtifact) {
    return CppFileTypes.headerDiscoveryRequired(sourceArtifact)
        && !featureConfiguration.isEnabled(CppRuleClasses.PARSE_SHOWINCLUDES);
  }

  /** Returns the environment variables that should be set for C++ compile actions. */
  protected Map<String, String> getEnvironment() {
    return featureConfiguration.getEnvironmentVariables(actionName, variables);
  }

  /** Returns the tool path for the compilation based on the current feature configuration. */
  @VisibleForTesting
  public String getToolPath() {
    Preconditions.checkArgument(
        featureConfiguration.actionIsConfigured(actionName),
        "Expected action_config for '%s' to be configured",
        actionName);
    return featureConfiguration
        .getToolForAction(actionName)
        .getToolPath(crosstoolTopPathFragment)
        .getPathString();
  }

  /**
   * @param overwrittenVariables: Variables that will overwrite original build variables. When null,
   *     unmodified original variables are used.
   */
  protected List<String> getArguments(
      @Nullable CcToolchainFeatures.Variables overwrittenVariables) {
    List<String> commandLine = new ArrayList<>();

    // first: The command name.
    commandLine.add(getToolPath());

    // second: The compiler options.
    commandLine.addAll(getCompilerOptions(overwrittenVariables));
    return commandLine;
  }

  public List<String> getCompilerOptions(
      @Nullable CcToolchainFeatures.Variables overwrittenVariables) {
    List<String> options = new ArrayList<>();

    CcToolchainFeatures.Variables updatedVariables = variables;
    if (variables != null && overwrittenVariables != null) {
      CcToolchainFeatures.Variables.Builder variablesBuilder =
          new CcToolchainFeatures.Variables.Builder(variables);
      variablesBuilder.addAllNonTransitive(overwrittenVariables);
      updatedVariables = variablesBuilder.build();
    }
    addFilteredOptions(
        options, featureConfiguration.getPerFeatureExpansions(actionName, updatedVariables));

    return options;
  }

  // For each option in 'in', add it to 'out' unless it is matched by the 'coptsFilter' regexp.
  private void addFilteredOptions(
      List<String> out, List<Pair<String, List<String>>> expandedFeatures) {
    for (Pair<String, List<String>> pair : expandedFeatures) {
      if (pair.getFirst().equals(CppRuleClasses.UNFILTERED_COMPILE_FLAGS_FEATURE_NAME)) {
        out.addAll(pair.getSecond());
        continue;
      }

      pair.getSecond().stream().filter(coptsFilter::passesFilter).forEachOrdered(out::add);
    }
  }

  public Artifact getSourceFile() {
    return sourceFile;
  }

  public DotdFile getDotdFile() {
    return dotdFile;
  }

  public Variables getVariables() {
    return variables;
  }

  /**
   * Returns all user provided copts flags.
   *
   * TODO(b/64108724): Get rid of this method when we don't need to parse copts to collect include
   * directories anymore (meaning there is a way of specifying include directories using an
   * explicit attribute, not using platform-dependent garbage bag that copts is).
   */
  public ImmutableList<String> getCopts() {
    if (variables.isAvailable(CcCompilationHelper.USER_COMPILE_FLAGS_VARIABLE_NAME)) {
      return Variables.toStringList(
          variables, CcCompilationHelper.USER_COMPILE_FLAGS_VARIABLE_NAME);
    } else {
      return ImmutableList.of();
    }
  }

  public static Builder builder(
      Artifact sourceFile,
      CoptsFilter coptsFilter,
      String actionName,
      PathFragment crosstoolTopPathFragment,
      DotdFile dotdFile) {
    return new Builder(sourceFile, coptsFilter, actionName, crosstoolTopPathFragment, dotdFile);
  }

  /** A builder for a {@link CompileCommandLine}. */
  public static final class Builder {
    private final Artifact sourceFile;
    private CoptsFilter coptsFilter;
    private FeatureConfiguration featureConfiguration;
    private CcToolchainFeatures.Variables variables = Variables.EMPTY;
    private final String actionName;
    private final PathFragment crosstoolTopPathFragment;
    @Nullable private final DotdFile dotdFile;

    public CompileCommandLine build() {
      return new CompileCommandLine(
          Preconditions.checkNotNull(sourceFile),
          Preconditions.checkNotNull(coptsFilter),
          Preconditions.checkNotNull(featureConfiguration),
          Preconditions.checkNotNull(crosstoolTopPathFragment),
          Preconditions.checkNotNull(variables),
          Preconditions.checkNotNull(actionName),
          dotdFile);
    }

    private Builder(
        Artifact sourceFile,
        CoptsFilter coptsFilter,
        String actionName,
        PathFragment crosstoolTopPathFragment,
        DotdFile dotdFile) {
      this.sourceFile = sourceFile;
      this.coptsFilter = coptsFilter;
      this.actionName = actionName;
      this.crosstoolTopPathFragment = crosstoolTopPathFragment;
      this.dotdFile = dotdFile;
    }

    /** Sets the feature configuration for this compile action. */
    public Builder setFeatureConfiguration(FeatureConfiguration featureConfiguration) {
      this.featureConfiguration = featureConfiguration;
      return this;
    }

    public Builder setVariables(Variables variables) {
      this.variables = variables;
      return this;
    }

    @VisibleForTesting
    Builder setCoptsFilter(CoptsFilter filter) {
      this.coptsFilter = Preconditions.checkNotNull(filter);
      return this;
    }
  }
}
