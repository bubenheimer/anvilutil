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

import com.google.auto.service.AutoService
import com.squareup.anvil.annotations.ContributesTo
import com.squareup.anvil.annotations.ExperimentalAnvilApi
import com.squareup.anvil.compiler.api.AnvilContext
import com.squareup.anvil.compiler.api.CodeGenerator
import com.squareup.anvil.compiler.api.GeneratedFile
import com.squareup.anvil.compiler.api.createGeneratedFile
import com.squareup.anvil.compiler.internal.buildFile
import com.squareup.anvil.compiler.internal.decapitalize
import com.squareup.anvil.compiler.internal.fqName
import com.squareup.anvil.compiler.internal.reference.*
import com.squareup.anvil.compiler.internal.safePackageString
import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.AnnotationSpec.UseSiteTarget.GET
import com.squareup.kotlinpoet.KModifier.ABSTRACT
import dagger.Binds
import dagger.Module
import dagger.Provides
import org.bubenheimer.anvil.annotations.Provisions
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtFile
import java.io.File

@AutoService(CodeGenerator::class)
internal class ProvisionsCodeGenerator : CodeGenerator {
    override fun isApplicable(context: AnvilContext) = !context.generateFactoriesOnly

    override fun generateCode(
            codeGenDir: File,
            module: ModuleDescriptor,
            projectFiles: Collection<KtFile>
    ): Collection<GeneratedFile> = projectFiles
            .classAndInnerClassReferences(module)
            .mapNotNull {
                when {
                    it.isAnnotatedWith(annotationFqName) -> processProvisionedClass(
                            clazz = it,
                            codeGenDir = codeGenDir
                    )

                    it.isAnnotatedWith(daggerModuleFqName) -> processProvisionedModuleMembers(
                            clazz = it,
                            codeGenDir = codeGenDir
                    )

                    else -> null
                }
            }
            .toList()

    private fun processProvisionedModuleMembers(
            clazz: ClassReference,
            codeGenDir: File
    ): GeneratedFile? {
        val allModuleClasses: Sequence<ClassReference> =
                (clazz.companionObjects() + clazz).asSequence()

        val functionProvisions: Sequence<Provision> = allModuleClasses
                .flatMap { it.functions }
                .filterForProvisionsAnnotations()
                .flatMap {
                    it.annotations
                            .checkRequiredAnnotations(it)
                            .extractProvisions(
                                    defaultIdentifier = it.name,
                                    typeName = it.returnType().asTypeName()
                            )
                }

        val propertyProvisions: Sequence<Provision> = allModuleClasses
                .flatMap { it.properties }
                .filterForProvisionsAnnotations()
                .flatMap {
                    it.annotations
                            .checkRequiredAnnotations(it)
                            .extractProvisions(
                                    defaultIdentifier = it.name,
                                    typeName = it.type().asTypeName()
                            )
                }

        val provisions: List<Provision> = (functionProvisions + propertyProvisions).toList()

        if (provisions.isEmpty()) return null

        val defaultScopes: Collection<ClassReference> = clazz.annotations
                .asSequence()
                .filter { it.fqName == contributesToFqName }
                .map { it.scope() }
                .toList()

        return createGeneratedFile(
                codeGenDir, clazz, provisions, defaultScopes
        )
    }

    private fun processProvisionedClass(
            clazz: ClassReference.Psi,
            codeGenDir: File
    ): GeneratedFile {
        val provisions: Sequence<Provision> = clazz.annotations
                .extractProvisions(
                        defaultIdentifier = clazz.shortName.decapitalize(),
                        typeName = clazz.asTypeName()
                )

        return createGeneratedFile(
                codeGenDir = codeGenDir,
                clazz = clazz,
                provisions = provisions.toList()
        )
    }

    private fun createGeneratedFile(
            codeGenDir: File,
            clazz: ClassReference,
            provisions: Collection<Provision>,
            defaultScopes: Collection<ClassReference> = emptyList()
    ): GeneratedFile {
        val scopedProvisions: Map<ClassReference, Collection<Provision>> =
                scopedProvisions(provisions, defaultScopes)

        val packageName: String = clazz.packageFqName.safePackageString()

        val baseClassName: String =
                clazz.generateClassName(suffix = "ProvisionsCodeGen").relativeClassName.asString()

        val content: String = FileSpec.buildFile(packageName, baseClassName) {
            scopedProvisions.onEachIndexed { index, entry ->
                val scope: ClassReference = entry.key

                val contributesToAnnotation: AnnotationSpec = AnnotationSpec
                        .builder(ContributesTo::class)
                        .addMember("${scope.fqName.asString()}::class")
                        .build()

                val className: String = baseClassName + index + "Component"

                addType(
                        TypeSpec
                                .interfaceBuilder(ClassName(packageName, className))
                                .addAnnotation(contributesToAnnotation)
                                .apply {
                                    entry.value.forEach {
                                        addProperty(
                                                PropertySpec.builder(
                                                        it.name,
                                                        it.typeName,
                                                        ABSTRACT
                                                )
                                                        .apply {
                                                            it.qualifiers.forEach {
                                                                addAnnotation(
                                                                        it.toAnnotationSpec().toBuilder()
                                                                                .useSiteTarget(GET)
                                                                                .build()
                                                                )
                                                            }
                                                        }
                                                        .build()
                                        )
                                    }
                                }.build()
                )
            }
        }

        return createGeneratedFile(
                codeGenDir = codeGenDir,
                packageName = packageName,
                fileName = baseClassName,
                content = content
        )
    }
}

private data class Provision(
        val annotation: AnnotationReference,
        val name: String,
        val typeName: TypeName,
        val qualifiers: Sequence<AnnotationReference>,
        val scope: ClassReference?
)

private fun <T : AnnotatedReference> Sequence<T>.filterForProvisionsAnnotations(): Sequence<T> =
        filter { it.isAnnotatedWith(annotationFqName) }

private fun List<AnnotationReference>.checkRequiredAnnotations(
        functionReference: FunctionReference
): List<AnnotationReference> = if (checkRequiredAnnotations()) this
else throw AnvilCompilationExceptionFunctionReference(functionReference, requiredAnnotationsError)

private fun List<AnnotationReference>.checkRequiredAnnotations(
        propertyReference: PropertyReference
): List<AnnotationReference> = if (checkRequiredAnnotations()) this
else throw AnvilCompilationExceptionPropertyReference(propertyReference, requiredAnnotationsError)

private fun List<AnnotationReference>.checkRequiredAnnotations(): Boolean =
        any { it.fqName == daggerProvidesFqName || it.fqName == daggerBindsFqName }

private const val requiredAnnotationsError: String =
        "Provisions annotation may only appear on elements annotated with @Provides or @Binds"

private fun List<AnnotationReference>.extractProvisions(
        defaultIdentifier: String,
        typeName: TypeName
): Sequence<Provision> {
    val qualifiers: Sequence<AnnotationReference> = asSequence().filter { it.isQualifier() }

    return asSequence()
            .filter { it.fqName == annotationFqName }
            .map {
                val scope: ClassReference? = it.scopeOrNull()

                val name: String = it.argumentAt("name", 1)?.value() ?: defaultIdentifier

                Provision(
                        annotation = it,
                        name = name,
                        typeName = typeName,
                        qualifiers = qualifiers,
                        scope = scope
                )
            }
}

private fun scopedProvisions(
        provisions: Collection<Provision>,
        defaultScopes: Collection<ClassReference> = emptyList()
): Map<ClassReference, Collection<Provision>> =
        buildMap<ClassReference, MutableCollection<Provision>> {
            provisions.forEach { provision ->
                val targetScopes: Collection<ClassReference> = provision.scope?.let { listOf(it) }
                        ?: defaultScopes.ifEmpty {
                            throw AnvilCompilationExceptionAnnotationReference(
                                    provision.annotation,
                                    "No default scope available"
                            )
                        }

                targetScopes.forEach {
                    get(it)?.add(provision) ?: put(it, mutableListOf(provision))
                }
            }
        }

private val daggerModuleFqName = Module::class.fqName

private val annotationFqName: FqName = Provisions::class.fqName

private val daggerProvidesFqName = Provides::class.fqName

private val daggerBindsFqName = Binds::class.fqName

private val contributesToFqName: FqName = ContributesTo::class.fqName
