/**
 * The dataset manifest: a small JSON document, separate from the packed entity-type files, that
 * records a release's format/generator/dataset versions and lists its available entity types.
 * {@link com.marksayson.deletionchecker.manifest.DatasetManifest} is the parsed, verified form;
 * {@link com.marksayson.deletionchecker.manifest.ManifestJson} and
 * {@link com.marksayson.deletionchecker.manifest.ManifestWriter} handle its JSON encoding.
 */
package com.marksayson.deletionchecker.manifest;
