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

@file:OptIn(ExperimentalAnvilApi::class)

package org.bubenheimer.anvil.generator

import com.squareup.anvil.annotations.ContributesTo
import com.squareup.anvil.annotations.ExperimentalAnvilApi
import com.squareup.anvil.compiler.api.AnvilContext
import com.squareup.anvil.compiler.api.CodeGenerator
import com.squareup.anvil.compiler.api.GeneratedFile
import com.squareup.anvil.compiler.api.createGeneratedFile
import com.squareup.anvil.compiler.internal.buildFile
import com.squareup.anvil.compiler.internal.fqName
import com.squareup.anvil.compiler.internal.reference.ClassReference
import com.squareup.anvil.compiler.internal.reference.asTypeName
import com.squareup.anvil.compiler.internal.reference.classAndInnerClassReferences
import com.squareup.anvil.compiler.internal.reference.generateClassName
import com.squareup.anvil.compiler.internal.safePackageString
import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.AnnotationSpec.UseSiteTarget.GET
import com.squareup.kotlinpoet.KModifier.ABSTRACT
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import dagger.Binds
import dagger.MembersInjector
import dagger.Module
import dagger.multibindings.ClassKey
import dagger.multibindings.IntoMap
import org.bubenheimer.anvil.annotations.AutoInject
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtFile
import java.io.File

internal class AutoInjectCodeGenerator : CodeGenerator {
    override fun isApplicable(context: AnvilContext) = !context.generateFactoriesOnly

    override fun generateCode(
            codeGenDir: File,
            module: ModuleDescriptor,
            projectFiles: Collection<KtFile>
    ): Collection<GeneratedFile> = projectFiles
            .classAndInnerClassReferences(module)
            .filter { it.isAnnotatedWith(annotationFqName) }
            .map { clazz: ClassReference ->
                val scope: String = clazz.annotations.singleOrNull { it.fqName == annotationFqName }
                        ?.scope()
                        ?.fqName
                        ?.asString()
                        ?: error("")

                val packageName: String = clazz.packageFqName.safePackageString()

                val clazzTypeName: TypeName = clazz.asTypeName()

                val fqClazzName: String = clazz.fqName.asString()

                val baseClassName: String =
                        clazz.generateClassName(suffix = "AICodeGen").relativeClassName.asString()

                val contributesToAnnotation: AnnotationSpec = AnnotationSpec
                        .builder(ContributesTo::class)
                        .addMember("$scope::class")
                        .build()

                val content: String = FileSpec.buildFile(packageName, baseClassName) {
                    addType(
                            TypeSpec.interfaceBuilder(ClassName(packageName, baseClassName + "Module"))
                                    .addAnnotation(Module::class)
                                    .addAnnotation(contributesToAnnotation)
                                    .addProperty(
                                            PropertySpec.builder(
                                                    "membersInjector",
                                                    membersInjectorClassNameParameterized,
                                                    ABSTRACT
                                            )
                                                    .addAnnotation(
                                                            AnnotationSpec.builder(Binds::class)
                                                                    .useSiteTarget(GET)
                                                                    .build()
                                                    )
                                                    .addAnnotation(
                                                            AnnotationSpec.builder(IntoMap::class)
                                                                    .useSiteTarget(GET)
                                                                    .build()
                                                    )
                                                    .addAnnotation(
                                                            AnnotationSpec.builder(ClassKey::class)
                                                                    .addMember("$fqClazzName::class")
                                                                    .useSiteTarget(GET)
                                                                    .build()
                                                    )
                                                    .receiver(
                                                            membersInjectorClassName.parameterizedBy(clazzTypeName)
                                                    )
                                                    .build()
                                    )
                                    .build()
                    )

                    addType(
                            TypeSpec
                                    .interfaceBuilder(ClassName(packageName, baseClassName + "Component"))
                                    .addAnnotation(contributesToAnnotation)
                                    .addFunction(
                                            FunSpec.builder("inject")
                                                    .addModifiers(ABSTRACT)
                                                    .receiver(clazzTypeName)
                                                    .build()
                                    )
                                    .build()
                    )
                }

                createGeneratedFile(
                        codeGenDir = codeGenDir,
                        packageName = packageName,
                        fileName = baseClassName,
                        content = content
                )
            }
            .toList()
}

private val annotationFqName: FqName = AutoInject::class.fqName

private val membersInjectorClassName: ClassName = MembersInjector::class.asClassName()

private val membersInjectorClassNameParameterized = membersInjectorClassName.parameterizedBy(STAR)
