/*
 * Copyright © 2016 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package io.cdap.hub.publisher;

import com.google.api.gax.paging.Page;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.common.collect.ImmutableMap;
import com.google.common.hash.Hashing;
import com.google.common.io.BaseEncoding;
import com.google.common.io.Files;
import com.google.common.net.MediaType;
import io.cdap.hub.GoogleCloudStorageClient;
import io.cdap.hub.Hub;
import io.cdap.hub.Package;
import io.cdap.hub.SignedFile;
import io.cdap.hub.spec.CategoryMeta;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.activation.FileTypeMap;
import javax.activation.MimetypesFileTypeMap;
import javax.annotation.Nullable;

/**
 * Publish packages to GCS bucket.
 */
public class GCSPublisher implements Publisher {

  private static final Logger LOG = LoggerFactory.getLogger(GCSPublisher.class);
  private static final FileTypeMap fileTypeMap = MimetypesFileTypeMap.getDefaultFileTypeMap();
  private final String bucket;
  private final String prefix;

  private final boolean forcePush;
  private final boolean dryrun;
  private final Set<String> updatedKeys;

  private Storage storage;

  private GCSPublisher(
      GoogleCloudStorageClient googleCloudStorageClient,
      String projectId, String bucket, String prefix, boolean dryrun, boolean forcePush) {
    this.storage = googleCloudStorageClient.createStorageConnection(projectId);
    this.bucket = bucket;
    this.prefix = prefix;
    this.dryrun = dryrun;
    this.forcePush = forcePush;
    this.updatedKeys = new HashSet<>();
  }

  @Override
  public void publish(Hub hub) throws Exception {
    updatedKeys.clear();

    List<Package> packages = hub.getPackages();
    for (Package pkg : packages) {
      publishPackage(pkg);
    }
    for (CategoryMeta categoryMeta : hub.getCategories()) {
      publishCategory(categoryMeta);
    }

    StringBuilder keyBuilder = new StringBuilder();
    if (prefix != null && !prefix.equals("")) {
      keyBuilder.append(prefix);
      keyBuilder.append("/");
    }
    String key = keyBuilder.toString();

    LOG.info("Publishing package catalog");
    putFilesIfChanged(key, hub.getPackageCatalog());
    LOG.info("Publishing category catalog");
    putFilesIfChanged(key, hub.getCategoryCatalog());
  }

  private void publishPackage(Package pkg) throws Exception {
    LOG.info("Publishing package {}-{}", pkg.getName(), pkg.getVersion());
    StringBuilder keyPrefixBuilder = new StringBuilder();
    if (prefix != null && !prefix.equals("")) {
      keyPrefixBuilder.append(prefix);
      keyPrefixBuilder.append("/");
    }

    keyPrefixBuilder.append("packages");
    keyPrefixBuilder.append("/");
    keyPrefixBuilder.append(pkg.getName());
    keyPrefixBuilder.append("/");
    keyPrefixBuilder.append(pkg.getVersion());
    keyPrefixBuilder.append("/");

    String keyPrefix = keyPrefixBuilder.toString();

    putFilesIfChanged(keyPrefix, pkg.getIcon());
    putFilesIfChanged(keyPrefix, pkg.getLicense());
    putFilesIfChanged(keyPrefix, pkg.getSpec().getFile(), pkg.getSpec().getSignature());
    if (pkg.getArchive() != null) {
      putFilesIfChanged(keyPrefix, pkg.getArchive().getFile(), pkg.getArchive().getSignature());
    }
    for (SignedFile file : pkg.getFiles()) {
      putFilesIfChanged(keyPrefix, file.getFile(), file.getSignature());
    }

    Page<Blob> blobs =
        storage.list(
            this.bucket,
            Storage.BlobListOption.prefix(keyPrefix),
            Storage.BlobListOption.currentDirectory());

    for (Blob blob : blobs.iterateAll()) {

      String objectKey = blob.getName();
      String name = objectKey.substring(keyPrefixBuilder.length());
      if (!pkg.getFileNames().contains(name)) {
        if (!dryrun) {
          LOG.info("Deleting object {} from gcs bucket since it does not exist in the package anymore.", objectKey);
          storage.delete(blob.getBlobId());
        } else {
          LOG.info(
              "dryrun - would have deleted {} from gcs bucket since it does not exist in the package anymore.",
              objectKey);
        }
      }
    }
  }

  private void publishCategory(CategoryMeta categoryMeta) throws Exception {
    String keyPrefix = String.format("%s/categories/%s/", prefix, categoryMeta.getName());
    putFilesIfChanged(keyPrefix, categoryMeta.getIcon());
  }

  // if the specified file has changed, put it plus all extra files on gcs bucket.
  private void putFilesIfChanged(String keyPrefix, @Nullable File file, File... extraFiles) throws IOException {
    if (file != null && shouldPush(keyPrefix, file)) {
      putFile(keyPrefix, file);
      for (File extraFile : extraFiles) {
        if (extraFile != null) {
          putFile(keyPrefix, extraFile);
        }
      }
    }
  }

  // check if the file in the gcs bucket has a different md5 or the file length.
  boolean shouldPush(String keyPrefix, File file) throws IOException {
    if (forcePush) {
      return true;
    }
    String key = keyPrefix + file.getName();

    Page<Blob> blobs =
        storage.list(
            this.bucket,
            Storage.BlobListOption.prefix(key),
            Storage.BlobListOption.currentDirectory());
    for (Blob blob : blobs.iterateAll()) {
      long existingContentFileLength = blob.getSize();
      long fileLength = file.length();
      String md5Hex = BaseEncoding.base16().encode(Files.hash(file, Hashing.md5()).asBytes());
      if (existingContentFileLength == fileLength &&
          blob.getMd5() != null && blob.getMd5ToHexString().equalsIgnoreCase(md5Hex)) {
        LOG.info("{} has not changed, skipping upload to GCS bucket.", file);
        return false;
      }
    }
    return true;
  }

  private void putFile(String keyPrefix, File file) throws IOException {
    String ext = Files.getFileExtension(file.getName());
    String contentType;
    switch (ext) {
      case "json":
        contentType = MediaType.JSON_UTF_8.withoutParameters().toString();
        break;
      case "txt":
        contentType = MediaType.PLAIN_TEXT_UTF_8.withoutParameters().toString();
        break;
      case "png":
        contentType = MediaType.PNG.withoutParameters().toString();
        break;
      case "asc":
        contentType = MediaType.PLAIN_TEXT_UTF_8.withoutParameters().toString();
        break;
      default:
        contentType = fileTypeMap.getContentType(file);
    }

    String key = keyPrefix + file.getName();

    if (!dryrun) {
      LOG.info("put file {} into gcs bucket with key {}", file, key);
      BlobId blobId = BlobId.of(bucket, key);
      BlobInfo blobInfo = BlobInfo.newBuilder(blobId)
          .setMetadata(ImmutableMap.of("Content-Type", contentType))
          .build();
      storage.create(blobInfo, Files.toByteArray(file));
    } else {
      LOG.info("dryrun - would have put file {} into gcs bucket with key {}", file, key);
    }
    updatedKeys.add("/" + key);
  }

  public static Builder builder(GoogleCloudStorageClient googleCloudStorageClient, String projectId, String bucket) {
    return new Builder(googleCloudStorageClient, projectId, bucket);
  }

  /**
   * Builder to create the GCSPublisher.
   */
  public static class Builder {

    private GoogleCloudStorageClient googleCloudStorageClient;
    private final String projectId;
    private final String bucket;
    private String prefix;
    private boolean forcePush;
    private boolean dryrun;

    public Builder(GoogleCloudStorageClient googleCloudStorageClient, String projectId, String bucket) {
      this.googleCloudStorageClient = googleCloudStorageClient;
      this.projectId = projectId;
      this.bucket = bucket;
      this.forcePush = false;
      this.dryrun = false;
      this.prefix = "";
    }

    public Builder setPrefix(String prefix) {
      this.prefix = prefix;
      return this;
    }

    public Builder setForcePush(boolean forcePush) {
      this.forcePush = forcePush;
      return this;
    }

    public Builder setDryRun(boolean dryrun) {
      this.dryrun = dryrun;
      return this;
    }

    public GCSPublisher build() {
      return new GCSPublisher(googleCloudStorageClient, projectId, bucket, prefix, dryrun, forcePush);
    }
  }
}
