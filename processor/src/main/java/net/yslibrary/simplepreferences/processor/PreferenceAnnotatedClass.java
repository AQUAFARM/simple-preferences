package net.yslibrary.simplepreferences.processor;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import com.google.common.base.Strings;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import javax.annotation.processing.Filer;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.util.Elements;
import net.yslibrary.simplepreferences.annotation.Key;
import net.yslibrary.simplepreferences.annotation.Preferences;
import net.yslibrary.simplepreferences.processor.exception.ProcessingException;

/**
 * Created by yshrsmz on 2016/02/21.
 */
public class PreferenceAnnotatedClass {

  public final static String DEFAULT_PREFS = "default";
  private final static String SUFFIX = "Prefs";

  public final TypeElement annotatedElement;

  // used as SharedPreferences' name
  public final String preferenceName;

  // generated class name
  public final String preferenceClassName;
  public final String qualifiedPreferenceClassName;

  public final String packageName;

  public final List<KeyAnnotatedField> keys = new ArrayList<>();

  public PreferenceAnnotatedClass(TypeElement element, Elements elementUtils)
      throws IllegalStateException, ProcessingException {
    annotatedElement = element;
    Preferences annotation = annotatedElement.getAnnotation(Preferences.class);
    String value = annotation.value().trim();
    String simpleName = element.getSimpleName().toString();
    preferenceName = Strings.isNullOrEmpty(value) ? DEFAULT_PREFS : value;

    if (Strings.isNullOrEmpty(preferenceName)) {
      throw new IllegalStateException(String.format(
          "value() in @%s for Class %s is null, empty or target Class is anonymous class. You must specify value or class should be concrete.",
          Preferences.class.getSimpleName(), element.getQualifiedName()));
    }

    preferenceClassName = simpleName + SUFFIX;
    qualifiedPreferenceClassName = element.getQualifiedName().toString() + SUFFIX;

    PackageElement pkg = elementUtils.getPackageOf(element);
    packageName = pkg.isUnnamed() ? null : pkg.getQualifiedName().toString();

    try {
      List<KeyAnnotatedField> annotatedVariables = element.getEnclosedElements()
          .stream()
          .filter(variable -> variable.getAnnotation(Key.class) != null)
          .map(variable -> {
            try {
              return new KeyAnnotatedField((VariableElement) variable);
            } catch (ProcessingException e) {
              throw new RuntimeException(e);
            }
          })
          .collect(Collectors.toList());

      keys.addAll(annotatedVariables);
    } catch (RuntimeException e) {
      if (e.getCause() instanceof ProcessingException) {
        throw (ProcessingException) e.getCause();
      }
    }
  }

  public void generate(Elements elementUtils, Filer filer) throws IOException {
    TypeSpec.Builder classBuilder = TypeSpec.classBuilder(preferenceClassName)
        .addModifiers(Modifier.PUBLIC)
        .superclass(ClassName.get(annotatedElement));

    TypeName generatingClass = ClassName.get(packageName, preferenceClassName);

    // SharedPreferences field ---
    FieldSpec prefsField =
        FieldSpec.builder(SharedPreferences.class, "prefs", Modifier.PRIVATE, Modifier.FINAL)
            .build();
    classBuilder.addField(prefsField);

    // constructor
    MethodSpec.Builder constructorBuilder = MethodSpec.constructorBuilder()
        .addModifiers(Modifier.PUBLIC)
        .addParameter(Context.class, "context");
    constructorBuilder.beginControlFlow("if (context == null)")
        .addStatement("throw new NullPointerException($S)", "Context is Null!")
        .endControlFlow();
    if (preferenceName.equals(DEFAULT_PREFS)) {
      constructorBuilder.addStatement("prefs = $T.getDefaultSharedPreferences(context)",
          PreferenceManager.class);
    } else {
      constructorBuilder.addStatement(
          "prefs = context.getSharedPreferences($S, Context.MODE_PRIVATE)", preferenceName);
    }
    MethodSpec constructor = constructorBuilder.build();
    classBuilder.addMethod(constructor);

    // create method ---
    MethodSpec.Builder createMethod = MethodSpec.methodBuilder("create")
        .addModifiers(Modifier.PUBLIC)
        .addParameter(Context.class, "context")
        .returns(generatingClass);

    createMethod.beginControlFlow("if (context == null)")
        .addStatement("throw new NullPointerException($S)", "Context is Null!")
        .endControlFlow();

    createMethod.addStatement("return new $T(context)", generatingClass);

    classBuilder.addMethod(createMethod.build());

    // clear method ---
    MethodSpec.Builder clearMethod =
        MethodSpec.methodBuilder("clear").addModifiers(Modifier.PUBLIC);
    clearMethod.addStatement("prefs.edit().clear().apply()");

    classBuilder.addMethod(clearMethod.build());

    // keys
    keys.forEach(keyAnnotatedField -> {
      List<MethodSpec> methods = keyAnnotatedField.generate(prefsField, elementUtils);

      classBuilder.addMethods(methods);
    });

    JavaFile.builder(packageName, classBuilder.build()).build().writeTo(filer);
  }
}