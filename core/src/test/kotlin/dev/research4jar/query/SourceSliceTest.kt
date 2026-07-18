package dev.research4jar.query

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SourceSliceTest {
    private val fixture = """
        package com.example;

        import java.util.List;

        /** Outer type. */
        public class Outer {
            // A comment with braces { } that must not confuse slicing.
            private final String brace = "{ not a block }";

            public Outer() {
                this.helper();
            }

            /** Javadoc for the no-arg overload. */
            public int compute() {
                return 1; // } stray brace in comment
            }

            @Deprecated
            @SuppressWarnings("unchecked")
            public int compute(int seed) {
                Runnable r = new Runnable() {
                    @Override
                    public void run() {
                        // anonymous class body with compute-looking text
                    }
                };
                r.run();
                return seed + 1;
            }

            private void helper() {
            }

            public static class Inner {
                public String compute(String input) {
                    return input + "{inner}";
                }

                public interface Contract {
                    List<String> compute(List<String> values);
                }
            }
        }
    """.trimIndent()

    @Test
    fun `overloads return every matching body with signatures and line ranges`() {
        val sliced = SourceSlicer.slice(fixture, "com.example.Outer", "compute")
        assertEquals(2, sliced.slices.size)
        assertEquals("", sliced.note)
        val (first, second) = sliced.slices
        assertTrue(first.signature.contains("compute()"), first.signature)
        assertTrue(second.signature.contains("compute(int"), second.signature)
        // The Javadoc-extended slice starts at its comment.
        assertTrue(first.source.startsWith("    /** Javadoc for the no-arg overload. */"))
        // The annotated overload's slice includes its annotations.
        assertTrue(second.source.contains("@Deprecated"))
        assertTrue(second.source.trimEnd().endsWith("}"))
        assertTrue(first.startLine < first.endLine)
        assertTrue(second.startLine > first.endLine)
        // Line numbers index into the original file.
        val lines = fixture.split("\n")
        assertTrue(lines[second.endLine - 1].contains("}"))
    }

    @Test
    fun `nested class slicing resolves the binary name segment chain`() {
        val sliced = SourceSlicer.slice(fixture, "com.example.Outer${'$'}Inner", "compute")
        assertEquals(1, sliced.slices.size)
        assertTrue(sliced.slices.single().signature.contains("compute(String"))

        val contract = SourceSlicer.slice(
            fixture,
            "com.example.Outer${'$'}Inner${'$'}Contract",
            "compute",
        )
        assertEquals(1, contract.slices.size)
        // Interface methods have no body; the slice is the declaration itself.
        assertTrue(contract.slices.single().source.contains("List<String> compute"))
    }

    @Test
    fun `constructor slicing matches the simple class name and init`() {
        for (name in listOf("Outer", "<init>")) {
            val sliced = SourceSlicer.slice(fixture, "com.example.Outer", name)
            assertEquals(1, sliced.slices.size, name)
            assertTrue(sliced.slices.single().source.contains("this.helper()"), name)
        }
    }

    @Test
    fun `unknown method and unknown inner type fall back with notes`() {
        val missing = SourceSlicer.slice(fixture, "com.example.Outer", "doesNotExist")
        assertEquals(0, missing.slices.size)
        assertTrue(missing.note.contains("doesNotExist"), missing.note)

        // Anonymous-class binary segment cannot be resolved; the method is
        // still found anywhere in the file.
        val anonymous = SourceSlicer.slice(fixture, "com.example.Outer${'$'}1", "run")
        assertEquals(1, anonymous.slices.size)
        assertTrue(anonymous.note.contains("anywhere in the file"), anonymous.note)
    }

    @Test
    fun `enum slicing includes constant-body implementations`() {
        val enumFixture = """
            package com.example;

            public enum Op {
                PLUS {
                    @Override
                    public int apply(int a, int b) {
                        return a + b;
                    }
                },
                MINUS {
                    @Override
                    public int apply(int a, int b) {
                        return a - b;
                    }
                };

                /** Declared abstract; the bodies live in the constants. */
                public abstract int apply(int a, int b);
            }
        """.trimIndent()
        val sliced = SourceSlicer.slice(enumFixture, "com.example.Op", "apply")
        assertEquals(3, sliced.slices.size, sliced.slices.joinToString { it.signature })
        val sources = sliced.slices.joinToString("\n") { it.source }
        assertTrue(sources.contains("a + b"))
        assertTrue(sources.contains("a - b"))
        assertTrue(sources.contains("abstract int apply"))
        // Constant-body slices carry the constant name in the signature.
        assertTrue(sliced.slices.any { it.signature.startsWith("PLUS.") })
        assertTrue(sliced.slices.any { it.signature.startsWith("MINUS.") })
    }

    @Test
    fun `record constructor slicing covers compact and canonical forms`() {
        val recordFixture = """
            package com.example;

            public record Point(int x, int y) {
                public Point {
                    if (x < 0) {
                        throw new IllegalArgumentException("x");
                    }
                }

                public Point(int x) {
                    this(x, 0);
                }

                public int sum() {
                    return x + y;
                }
            }
        """.trimIndent()
        for (name in listOf("Point", "<init>")) {
            val constructors = SourceSlicer.slice(recordFixture, "com.example.Point", name)
            assertEquals(2, constructors.slices.size, name)
            val sources = constructors.slices.joinToString("\n") { it.source }
            assertTrue(sources.contains("IllegalArgumentException"), name)
            assertTrue(sources.contains("this(x, 0)"), name)
            assertTrue(
                constructors.slices.any { it.signature.contains("compact constructor") },
                constructors.slices.joinToString { it.signature },
            )
        }
        val method = SourceSlicer.slice(recordFixture, "com.example.Point", "sum")
        assertEquals(1, method.slices.size)
        assertTrue(method.slices.single().source.contains("x + y"))
    }

    @Test
    fun `unparseable source degrades to a whole-file note`() {
        val sliced = SourceSlicer.slice("this is } not { java", "com.example.Broken", "method")
        assertEquals(0, sliced.slices.size)
        assertTrue(sliced.note.contains("whole file"), sliced.note)
    }

    @Test
    fun `binary name candidates rewrite source-style inner names`() {
        assertEquals(listOf("com.example.Outer"), binaryNameCandidates("com.example.Outer").take(1))
        assertTrue("com.example.Outer${'$'}Inner" in binaryNameCandidates("com.example.Outer.Inner"))
        assertTrue(
            "com.example.Outer${'$'}Inner${'$'}Deep" in
                binaryNameCandidates("com.example.Outer.Inner.Deep"),
        )
        // Already-binary names stay first preference.
        assertEquals(
            "com.example.Outer${'$'}Inner",
            binaryNameCandidates("com.example.Outer${'$'}Inner").first(),
        )
    }
}
