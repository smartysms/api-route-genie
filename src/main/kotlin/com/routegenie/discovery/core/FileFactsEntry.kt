package com.routegenie.discovery.core

import com.routegenie.discovery.FileFacts
import com.routegenie.model.Diagnostic

/**
 * What one extraction of one file produced, valid for exactly one [stamp] of that file. [facts] is null when the file
 * holds no routes; [failure] is set when the provider threw, so a broken file is not retried on every refresh until it
 * changes. Holds no PSI, VirtualFile or Project.
 */
class FileFactsEntry(val stamp: Long, val facts: FileFacts?, val failure: Diagnostic?)
