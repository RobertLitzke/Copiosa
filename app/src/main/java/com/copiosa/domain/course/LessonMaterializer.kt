package com.copiosa.domain.course

/**
 * Turns an authored [AuthoredSegment.Didactic] into its serialized [DidacticSegment] — coordinates
 * only (slugs, infinitives), so what lands in `planJson` is small and re-derived at render time.
 * Pure, so the session builder's tests can cover it without a database.
 */
fun AuthoredSegment.Didactic.materialize(): DidacticSegment = DidacticSegment(
    title = title,
    blocks = blocks.map { block ->
        when (block) {
            is DidacticBlock.Prose -> ProseBlock(block.text)
            is DidacticBlock.TableRef -> TableBlock(
                tense = block.tense.slug,
                verbs = block.verbs,
                persons = block.persons?.map { it.slug },
            )

            is DidacticBlock.Examples -> ExamplesBlock(
                block.items.map {
                    ExampleItem(
                        infinitive = it.infinitive,
                        cell = it.cell?.slug,
                        sentence = it.sentence,
                        gloss = it.gloss,
                        note = it.note,
                    )
                },
            )
        }
    },
)
