/*
 * Licensed to CRATE Technology GmbH ("Crate") under one or more contributor
 * license agreements.  See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.  Crate licenses
 * this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial agreement.
 */

package io.crate.exceptions;

import org.elasticsearch.rest.RestStatus;

import java.util.Locale;

public class TableAlreadyExistsException extends CrateException {

    private static final String MESSAGE_TMPL = "The table '%s' already exists.";

    String tableName;

    public TableAlreadyExistsException(String tableName) {
        super(String.format(Locale.ENGLISH, MESSAGE_TMPL, tableName));
        this.tableName = tableName;
    }

    public TableAlreadyExistsException(String tableName, Throwable e) {
        super(String.format(Locale.ENGLISH, MESSAGE_TMPL, tableName), e);
        this.tableName = tableName;
    }

    @Override
    public int errorCode() {
        return 4093;
    }

    @Override
    public RestStatus status() {
        return RestStatus.CONFLICT;
    }

    @Override
    public Object[] args() {
        return new Object[]{tableName};
    }
}
