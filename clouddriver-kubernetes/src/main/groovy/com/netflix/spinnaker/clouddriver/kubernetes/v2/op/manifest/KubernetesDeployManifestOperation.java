/*
 * Copyright 2017 Google, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.netflix.spinnaker.clouddriver.kubernetes.v2.op.manifest;

import com.netflix.spinnaker.clouddriver.artifacts.ArtifactDownloader;
import com.netflix.spinnaker.clouddriver.data.task.Task;
import com.netflix.spinnaker.clouddriver.data.task.TaskRepository;
import com.netflix.spinnaker.clouddriver.kubernetes.KubernetesCloudProvider;
import com.netflix.spinnaker.clouddriver.kubernetes.v2.artifact.ArtifactReplacer;
import com.netflix.spinnaker.clouddriver.kubernetes.v2.artifact.ArtifactReplacer.ReplaceResult;
import com.netflix.spinnaker.clouddriver.kubernetes.v2.artifact.KubernetesArtifactConverter;
import com.netflix.spinnaker.clouddriver.kubernetes.v2.description.KubernetesResourceProperties;
import com.netflix.spinnaker.clouddriver.kubernetes.v2.description.KubernetesResourcePropertyRegistry;
import com.netflix.spinnaker.clouddriver.kubernetes.v2.description.manifest.KubernetesDeployManifestDescription;
import com.netflix.spinnaker.clouddriver.kubernetes.v2.description.manifest.KubernetesKind;
import com.netflix.spinnaker.clouddriver.kubernetes.v2.description.manifest.KubernetesManifest;
import com.netflix.spinnaker.clouddriver.kubernetes.v2.description.manifest.KubernetesManifestAnnotater;
import com.netflix.spinnaker.clouddriver.kubernetes.v2.description.manifest.KubernetesManifestSpinnakerRelationships;
import com.netflix.spinnaker.clouddriver.kubernetes.v2.op.OperationResult;
import com.netflix.spinnaker.clouddriver.kubernetes.v2.op.deployer.KubernetesHandler;
import com.netflix.spinnaker.clouddriver.kubernetes.v2.security.KubernetesV2Credentials;
import com.netflix.spinnaker.clouddriver.model.ArtifactProvider;
import com.netflix.spinnaker.clouddriver.names.NamerRegistry;
import com.netflix.spinnaker.clouddriver.orchestration.AtomicOperation;
import com.netflix.spinnaker.kork.artifacts.model.Artifact;
import com.netflix.spinnaker.moniker.Moniker;
import com.netflix.spinnaker.moniker.Namer;
import org.apache.commons.lang.StringUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static com.netflix.spinnaker.clouddriver.kubernetes.v2.description.manifest.KubernetesDeployManifestDescription.Source.text;

public class KubernetesDeployManifestOperation implements AtomicOperation<OperationResult> {
  private final KubernetesDeployManifestDescription description;
  private final KubernetesV2Credentials credentials;
  private final ArtifactDownloader artifactDownloader;
  private final ArtifactProvider provider;
  private final Namer namer;
  private final KubernetesResourcePropertyRegistry registry;
  private static final String OP_NAME = "DEPLOY_KUBERNETES_MANIFEST";

  public KubernetesDeployManifestOperation(KubernetesDeployManifestDescription description, KubernetesResourcePropertyRegistry registry, ArtifactProvider provider, ArtifactDownloader artifactDownloader) {
    this.description = description;
    this.credentials = (KubernetesV2Credentials) description.getCredentials().getCredentials();
    this.registry = registry;
    this.provider = provider;
    this.artifactDownloader = artifactDownloader;
    this.namer = NamerRegistry.lookup()
        .withProvider(KubernetesCloudProvider.getID())
        .withAccount(description.getCredentials().getName())
        .withResource(KubernetesManifest.class);
  }

  private static Task getTask() {
    return TaskRepository.threadLocalTask.get();
  }

  @Override
  public OperationResult operate(List _unused) {
    getTask().updateStatus(OP_NAME, "Beginning deployment of manifest...");

    KubernetesManifest manifest;
    KubernetesDeployManifestDescription.Source source = description.getSource();
    source = source == null ? text : source;
    switch (source) {
      case text:
        manifest = description.getManifest();
        break;
      case artifact:
        try {
          manifest = artifactDownloader.downloadAsYaml(description.getManifestArtifact(), KubernetesManifest.class);
        } catch (IOException e) {
          throw new IllegalStateException("Failed to fetch artifact '" + description.getManifestArtifact() + "'", e);
        }
        break;
      default:
        throw new IllegalArgumentException("Unsupported artifact source: " + source);
    }

    if (StringUtils.isEmpty(manifest.getNamespace())) {
      manifest.setNamespace(credentials.getDefaultNamespace());
    }
    List<Artifact> artifacts = description.getArtifacts();
    if (artifacts == null) {
      artifacts = new ArrayList<>();
    }

    KubernetesResourceProperties properties = findResourceProperties(manifest);
    boolean versioned = description.getVersioned() == null ? properties.isVersioned() : description.getVersioned();
    KubernetesArtifactConverter converter = versioned ? properties.getVersionedConverter() : properties.getUnversionedConverter();
    KubernetesHandler deployer = properties.getHandler();

    Artifact artifact = converter.toArtifact(provider, manifest);
    Moniker moniker = description.getMoniker();
    KubernetesManifestSpinnakerRelationships relationships = description.getRelationships();

    getTask().updateStatus(OP_NAME, "Annotating manifest with artifact, relationships & moniker...");
    KubernetesManifestAnnotater.annotateManifest(manifest, artifact);
    KubernetesManifestAnnotater.annotateManifest(manifest, relationships);
    namer.applyMoniker(manifest, moniker);

    getTask().updateStatus(OP_NAME, "Setting a resource name...");
    manifest.setName(converter.getDeployedName(artifact));

    getTask().updateStatus(OP_NAME, "Swapping out artifacts from context...");
    ReplaceResult replaceResult = deployer.replaceArtifacts(manifest, artifacts);
    manifest = replaceResult.getManifest();

    getTask().updateStatus(OP_NAME, "Submitting manifest to kubernetes master...");
    OperationResult result = deployer.deployAugmentedManifest(credentials, manifest);

    result.getCreatedArtifacts().add(artifact);
    result.getBoundArtifacts().addAll(replaceResult.getBoundArtifacts());

    return result;
  }

  private KubernetesResourceProperties findResourceProperties(KubernetesManifest manifest) {
    KubernetesKind kind = manifest.getKind();
    getTask().updateStatus(OP_NAME, "Finding deployer for " + kind + "...");
    return registry.get(kind);
  }
}
