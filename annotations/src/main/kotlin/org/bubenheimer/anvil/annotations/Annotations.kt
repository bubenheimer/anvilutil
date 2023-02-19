/*
 * Copyright (c) 2015-2023 Uli Bubenheimer
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.bubenheimer.anvil.annotations

import kotlin.annotation.AnnotationRetention.RUNTIME
import kotlin.annotation.AnnotationTarget.*
import kotlin.reflect.KClass

@Target(CLASS)
@Retention(RUNTIME)
annotation class AutoInject(
    val scope: KClass<*>
)

@Target(CLASS, FUNCTION, PROPERTY_GETTER)
@Retention(RUNTIME)
@Repeatable
annotation class Provisions(
    val scope: KClass<*> = Unit::class,
    val name: String = ""
)

@Target(FUNCTION, PROPERTY_GETTER)
@Retention(RUNTIME)
@Repeatable
annotation class AndBinds(
    val boundType: KClass<*>
)
