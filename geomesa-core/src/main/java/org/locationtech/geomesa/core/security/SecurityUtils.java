/*
 * Copyright 2015 Commonwealth Computer Research, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.locationtech.geomesa.core.security;

import com.google.common.base.Joiner;
import org.opengis.feature.simple.SimpleFeature;

public class SecurityUtils {

    public static final String FEATURE_VISIBILITY = "geomesa.feature.visibility";

    public static final SimpleFeature setFeatureVisibility(SimpleFeature feature, String visibility) {
        feature.getUserData().put(FEATURE_VISIBILITY, visibility);
        return feature;
    }

    public static final SimpleFeature setFeatureVisibilities(SimpleFeature feature, String... visibilities) {
        String and = Joiner.on("&").join(visibilities);
        return setFeatureVisibility(feature, and);
    }
}
