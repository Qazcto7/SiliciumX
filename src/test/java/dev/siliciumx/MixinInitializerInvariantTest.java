package dev.siliciumx;

import dev.siliciumx.client.mixin.cull.EntityOcclusionMixin;
import dev.siliciumx.mixin.chunk.ServerChunkCacheMixin;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.attribute.RuntimeInvisibleAnnotationsAttribute;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.reflect.AccessFlag;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that no SiliciumX {@code @Mixin} class defines inline non-default instance field
 * initializers ({@code PUTFIELD} inside {@code <init>}).
 *
 * <p>SpongePowered Mixin slices {@code <init>} initializers via {@code LineNumberTable} ranges,
 * which javac's line attribution for annotated or multi-line fields can truncate—silently dropping
 * earlier {@code @Unique} initializers at runtime. All Mixin instance state must instead be
 * initialized explicitly via {@code @Inject(method = "<init>...", at = @At("RETURN"))} and/or lazy
 * null-safe helpers.</p>
 */
class MixinInitializerInvariantTest {
	private static final String MIXIN_DESCRIPTOR = "Lorg/spongepowered/asm/mixin/Mixin;";

	@Test
	void noMixinClassHasInlineInstanceFieldInitializers() throws IOException, URISyntaxException {
		Set<Path> classRoots = new LinkedHashSet<>();
		classRoots.add(Path.of(ServerChunkCacheMixin.class.getProtectionDomain().getCodeSource().getLocation().toURI()));
		classRoots.add(Path.of(EntityOcclusionMixin.class.getProtectionDomain().getCodeSource().getLocation().toURI()));

		List<String> violations = new ArrayList<>();
		int inspectedMixins = 0;

		for (Path root : classRoots) {
			if (!Files.isDirectory(root)) {
				continue;
			}
			try (Stream<Path> walk = Files.walk(root)) {
				List<Path> classFiles = walk
					.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".class"))
					.sorted()
					.toList();
				for (Path classFile : classFiles) {
					ClassModel model = ClassFile.of().parse(classFile);
					if (model.flags().has(AccessFlag.INTERFACE) || !isMixin(model)) {
						continue;
					}
					inspectedMixins++;
					for (MethodModel method : model.methods()) {
						if (!method.methodName().equalsString("<init>")) {
							continue;
						}
						CodeModel code = method.code().orElse(null);
						if (code == null) {
							continue;
						}
						for (CodeElement elem : code) {
							if (elem instanceof FieldInstruction fi && fi.opcode() == Opcode.PUTFIELD) {
								violations.add(
									model.thisClass().asInternalName() + ".<init> initializes field " + fi.name().stringValue()
								);
							}
						}
					}
				}
			}
		}

		assertTrue(inspectedMixins >= 30, "Expected at least 30 compiled @Mixin classes, found " + inspectedMixins);
		assertFalse(
			!violations.isEmpty(),
			() -> "Mixin classes must not use inline instance initializers:\n" + String.join("\n", violations)
		);
	}

	private static boolean isMixin(ClassModel model) {
		RuntimeInvisibleAnnotationsAttribute attr =
			model.findAttribute(Attributes.runtimeInvisibleAnnotations()).orElse(null);
		if (attr == null) {
			return false;
		}
		return attr.annotations().stream().anyMatch(a -> a.className().equalsString(MIXIN_DESCRIPTOR));
	}
}
