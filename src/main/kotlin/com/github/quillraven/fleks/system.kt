package com.github.quillraven.fleks

import com.github.quillraven.fleks.collection.BitArray
import com.github.quillraven.fleks.collection.EntityComparator
import java.lang.reflect.Field
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KParameter
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.full.primaryConstructor

/**
 * An interval for an [IntervalSystem]. There are two kind of intervals:
 * - [EachFrame]
 * - [Fixed]
 *
 * [EachFrame] means that the [IntervalSystem] is updated every time the [world][World] gets updated.
 * [Fixed] means that the [IntervalSystem] is updated at a fixed rate given in seconds.
 */
sealed interface Interval
object EachFrame : Interval

/**
 * @param step the time in seconds when an [IntervalSystem] gets updated.
 */
data class Fixed(val step: Float) : Interval

/**
 * A basic system of a [world][World] without a context to [entities][Entity].
 * It is mandatory to implement [onTick] which gets called whenever the system gets updated
 * according to its [interval][Interval].
 *
 * If the system uses a [Fixed] interval then [onAlpha] can be overridden in case interpolation logic is needed.
 *
 * @param interval the [interval][Interval] in which the system gets updated. Default is [EachFrame].
 * @param enabled defines if the system gets updated when the [world][World] gets updated. Default is true.
 */
abstract class IntervalSystem(
    val interval: Interval = EachFrame,
    var enabled: Boolean = true
) {
    /**
     * Returns the [world][World] to which this system belongs.
     * This reference gets updated by the [SystemService] when the system gets created via reflection.
     */
    val world: World = World.EMPTY_WORLD

    private var accumulator: Float = 0.0f

    /**
     * Returns the time in seconds since the last time [onUpdate] was called.
     *
     * If the [interval] is [EachFrame] then the [world's][World] delta time is returned which is passed to [World.update].
     *
     * Otherwise, the [step][Fixed.step] value is returned.
     */
    val deltaTime: Float
        get() = if (interval is Fixed) interval.step else world.deltaTime

    /**
     * Updates the system according to its [interval]. This function gets called from [World.update] when
     * the system is [enabled].
     *
     * If the [interval] is [EachFrame] then [onTick] gets called.
     *
     * Otherwise, the world's [delta time][World.deltaTime] is analyzed and [onTick] is called at a fixed rate.
     * This could be multiple or zero times with a single call to [onUpdate]. At the end [onAlpha] is called.
     */
    open fun onUpdate() {
        when (interval) {
            is EachFrame -> onTick()
            is Fixed -> {
                accumulator += world.deltaTime
                val stepRate = interval.step
                while (accumulator >= stepRate) {
                    onTick()
                    accumulator -= stepRate
                }

                onAlpha(accumulator / stepRate)
            }
        }
    }

    /**
     * Function that contains the update logic of the system. Gets called whenever this system should get processed
     * according to its [interval].
     */
    abstract fun onTick()

    /**
     * Optional function for interpolation logic when using a [Fixed] interval. This function is not called for
     * an [EachFrame] interval.
     *
     * @param alpha a value between 0 (inclusive) and 1 (exclusive) that describes the progress between two ticks.
     */
    open fun onAlpha(alpha: Float) = Unit
}

/**
 * A sorting type for an [IteratingSystem]. There are two sorting options:
 * - [Automatic]
 * - [Manual]
 *
 * [Automatic] means that the sorting of [entities][Entity] is happening automatically each time
 * [IteratingSystem.onTick] gets called.
 *
 * [Manual] means that sorting must be called programmatically by setting [IteratingSystem.doSort] to true.
 * [Entities][Entity] are then sorted the next time [IteratingSystem.onTick] gets called.
 */
sealed interface SortingType
object Automatic : SortingType
object Manual : SortingType

/**
 * An [IntervalSystem] of a [world][World] with a context to [entities][Entity]. It must be linked to a
 * [family][Family] using at least one of the [AllOf], [AnyOf] or [NoneOf] annotations.
 *
 * @param comparator an optional [EntityComparator] that is used to sort [entities][Entity].
 * Default value is an empty comparator which means no sorting.
 * @param sortingType the [type][SortingType] of sorting for entities when using a [comparator].
 * @param interval the [interval][Interval] in which the system gets updated. Default is [EachFrame].
 * @param enabled defines if the system gets updated when the [world][World] gets updated. Default is true.
 */
abstract class IteratingSystem(
    private val comparator: EntityComparator = EMPTY_COMPARATOR,
    private val sortingType: SortingType = Automatic,
    interval: Interval = EachFrame,
    enabled: Boolean = true
) : IntervalSystem(interval, enabled) {
    /**
     * Returns the [family][Family] of this system.
     * This reference gets updated by the [SystemService] when the system gets created via reflection.
     */
    private val family: Family = Family.EMPTY_FAMILY

    /**
     * Returns the [entityService][EntityService] of this system.
     * This reference gets updated by the [SystemService] when the system gets created via reflection.
     */
    @PublishedApi
    internal val entityService: EntityService = world.entityService

    /**
     * Flag that defines if sorting of [entities][Entity] will be performed the next time [onTick] is called.
     *
     * If a [comparator] is defined and [sortingType] is [Automatic] then this flag is always true.
     *
     * Otherwise, it must be set programmatically to perform sorting. The flag gets cleared after sorting.
     */
    var doSort = sortingType == Automatic && comparator != EMPTY_COMPARATOR

    /**
     * Updates an [entity] using the given [configuration] to add and remove components.
     */
    inline fun configureEntity(entity: Entity, configuration: EntityUpdateCfg.(Entity) -> Unit) {
        entityService.configureEntity(entity, configuration)
    }

    /**
     * Updates the [family] if needed and calls [onTickEntity] for each [entity][Entity] of the [family].
     * If [doSort] is true then [entities][Entity] are sorted using the [comparator] before calling [onTickEntity].
     */
    override fun onTick() {
        if (family.isDirty) {
            family.updateActiveEntities()
        }
        if (doSort) {
            doSort = sortingType == Automatic
            family.sort(comparator)
        }
        family.forEach { onTickEntity(it) }
    }

    /**
     * Function that contains the update logic for each [entity][Entity] of the system.
     */
    abstract fun onTickEntity(entity: Entity)

    /**
     * Optional function for interpolation logic when using a [Fixed] interval. This function is not called for
     * an [EachFrame] interval. Calls [onAlphaEntity] for each [entity][Entity] of the system.
     *
     * @param alpha a value between 0 (inclusive) and 1 (exclusive) that describes the progress between two ticks.
     */
    override fun onAlpha(alpha: Float) {
        family.forEach { onAlphaEntity(it, alpha) }
    }

    /**
     * Optional function for interpolation logic for each [entity][Entity] of the system.
     *
     * @param alpha a value between 0 (inclusive) and 1 (exclusive) that describes the progress between two ticks.
     */
    open fun onAlphaEntity(entity: Entity, alpha: Float) = Unit

    companion object {
        private val EMPTY_COMPARATOR = object : EntityComparator {
            override fun compare(entityA: Entity, entityB: Entity): Int = 0
        }
    }
}

/**
 * A service class for any [IntervalSystem] of a [world][World]. It is responsible to create systems using
 * constructor dependency injection. It also stores [systems] and updates [enabled][IntervalSystem.enabled] systems
 * each time [update] is called.
 *
 * @param world the [world][World] the service belongs to.
 * @param systemTypes the [systems][IntervalSystem] to be created.
 * @param injectables the required dependencies to create the [systems][IntervalSystem].
 */
class SystemService(
    world: World,
    systemTypes: List<KClass<out IntervalSystem>>,
    injectables: MutableMap<KClass<*>, Any>
) {
    @PublishedApi
    internal val systems: Array<IntervalSystem>

    init {
        // create systems
        val entityService = world.entityService
        val cmpService = world.componentService
        val allFamilies = mutableListOf<Family>()
        systems = Array(systemTypes.size) { sysIdx ->
            val sysType = systemTypes[sysIdx]

            val primaryConstructor = sysType.primaryConstructor ?: throw FleksSystemCreationException(
                sysType,
                "No primary constructor found"
            )
            // get constructor arguments
            val args = systemArgs(primaryConstructor, cmpService, injectables, sysType)
            // create new instance using arguments from above
            val newSystem = primaryConstructor.callBy(args)

            // set world reference of newly created system
            val worldField = field(newSystem, "world")
            worldField.isAccessible = true
            worldField.set(newSystem, world)

            if (sysType.isSubclassOf(IteratingSystem::class)) {
                // set family and entity service reference of newly created iterating system
                @Suppress("UNCHECKED_CAST")
                val family = family(sysType as KClass<out IteratingSystem>, entityService, cmpService, allFamilies)
                val famField = field(newSystem, "family")
                famField.isAccessible = true
                famField.set(newSystem, family)

                val eServiceField = field(newSystem, "entityService")
                eServiceField.isAccessible = true
                eServiceField.set(newSystem, entityService)
            }

            newSystem
        }
    }

    /**
     * Returns map of arguments for the given [primaryConstructor] of a [system][IntervalSystem].
     * Arguments are either [injectables] or [ComponentMapper] instances.
     *
     * @throws [FleksSystemCreationException] if [injectables] are missing for the [primaryConstructor].
     */
    private fun systemArgs(
        primaryConstructor: KFunction<IntervalSystem>,
        cmpService: ComponentService,
        injectables: MutableMap<KClass<*>, Any>,
        sysType: KClass<out IntervalSystem>
    ): Map<KParameter, Any?> {
        val args = primaryConstructor.parameters
            // filter out default value assignments in the constructor
            .filterNot { it.isOptional }
            // for any non-default value parameter assign the value of the injectables map
            // or a ComponentMapper of the ComponentService
            .associateWith {
                val paramClass = it.type.classifier as KClass<*>
                if (paramClass == ComponentMapper::class) {
                    val cmpClazz = it.type.arguments[0].type?.classifier as KClass<*>
                    cmpService.mapper(cmpClazz)
                } else {
                    injectables[it.type.classifier]
                }
            }


        val missingInjectables = args
            .filter { !it.key.type.isMarkedNullable && it.value == null }
            .map { it.key.type.classifier }
        if (missingInjectables.isNotEmpty()) {
            throw FleksSystemCreationException(
                sysType,
                "Missing injectables of type $missingInjectables"
            )
        }

        return args
    }

    /**
     * Creates or returns an already created [family][Family] for the given [IteratingSystem]
     * by analyzing the system's [AllOf], [AnyOf] and [NoneOf] annotations.
     *
     * @throws [FleksSystemCreationException] if the [IteratingSystem] does not contain at least one
     * [AllOf], [AnyOf] or [NoneOf] annotation.
     */
    private fun family(
        sysType: KClass<out IteratingSystem>,
        entityService: EntityService,
        cmpService: ComponentService,
        allFamilies: MutableList<Family>
    ): Family {
        val allOfAnn = sysType.findAnnotation<AllOf>()
        val allOfCmps = if (allOfAnn != null && allOfAnn.components.isNotEmpty()) {
            allOfAnn.components.map { cmpService.mapper(it) }
        } else {
            null
        }

        val noneOfAnn = sysType.findAnnotation<NoneOf>()
        val noneOfCmps = if (noneOfAnn != null && noneOfAnn.components.isNotEmpty()) {
            noneOfAnn.components.map { cmpService.mapper(it) }
        } else {
            null
        }

        val anyOfAnn = sysType.findAnnotation<AnyOf>()
        val anyOfCmps = if (anyOfAnn != null && anyOfAnn.components.isNotEmpty()) {
            anyOfAnn.components.map { cmpService.mapper(it) }
        } else {
            null
        }

        if ((allOfCmps == null || allOfCmps.isEmpty())
            && (noneOfCmps == null || noneOfCmps.isEmpty())
            && (anyOfCmps == null || anyOfCmps.isEmpty())
        ) {
            throw FleksSystemCreationException(
                sysType,
                "IteratingSystem must define at least one of AllOf, NoneOf or AnyOf"
            )
        }

        val allBs = if (allOfCmps == null) null else BitArray().apply { allOfCmps.forEach { this.set(it.id) } }
        val noneBs = if (noneOfCmps == null) null else BitArray().apply { noneOfCmps.forEach { this.set(it.id) } }
        val anyBs = if (anyOfCmps == null) null else BitArray().apply { anyOfCmps.forEach { this.set(it.id) } }

        var family = allFamilies.find { it.allOf == allBs && it.noneOf == noneBs && it.anyOf == anyBs }
        if (family == null) {
            family = Family(allBs, noneBs, anyBs)
            entityService.addEntityListener(family)
            allFamilies.add(family)
        }
        return family
    }

    /**
     * Returns a [Field] of name [fieldName] of the given [system].
     *
     * @throws [FleksSystemCreationException] if the [system] does not have a [Field] of name [fieldName].
     */
    private fun field(system: IntervalSystem, fieldName: String): Field {
        var sysClass: Class<*> = system::class.java
        var classField: Field? = null
        while (classField == null) {
            try {
                classField = sysClass.getDeclaredField(fieldName)
            } catch (e: NoSuchFieldException) {
                sysClass = sysClass.superclass
                if (sysClass == null) {
                    throw FleksSystemCreationException(system::class, "No '$fieldName' field found")
                }
            }

        }
        return classField
    }

    /**
     * Returns the specified [system][IntervalSystem].
     *
     * @throws [FleksNoSuchSystemException] if there is no such system.
     */
    inline fun <reified T : IntervalSystem> system(): T {
        systems.forEach { system ->
            if (system is T) {
                return system
            }
        }
        throw FleksNoSuchSystemException(T::class)
    }

    /**
     * Updates all [enabled][IntervalSystem.enabled] [systems][IntervalSystem] by calling
     * their [IntervalSystem.onUpdate] function.
     */
    fun update() {
        systems.forEach { system ->
            if (system.enabled) {
                system.onUpdate()
            }
        }
    }
}
