/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2018-2022 Agorapulse.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.agorapulse.micronaut.amazon.awssdk.itest.localstack.v1;

import com.agorapulse.micronaut.amazon.awssdk.itest.localstack.LocalstackContainerHolder;
import com.agorapulse.micronaut.aws.SafeAwsRegionProviderChain;
import com.amazonaws.SdkClientException;
import com.amazonaws.regions.AwsRegionProvider;
import io.micronaut.context.annotation.Primary;
import io.micronaut.context.annotation.Replaces;

import javax.inject.Singleton;

@Primary
@Singleton
@Replaces(SafeAwsRegionProviderChain.class)
public class LocalstackAwsRegionProvider extends AwsRegionProvider {

    private final LocalstackContainerHolder holder;

    public LocalstackAwsRegionProvider(LocalstackContainerHolder holder) {
        this.holder = holder;
    }

    @Override
    public String getRegion() throws SdkClientException {
        return holder.requireRunningContainer().getRegion();
    }
}
