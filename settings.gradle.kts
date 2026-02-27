rootProject.name = "auto-shipper-ai"

include(
    "shared",
    "catalog",
    "pricing",
    "vendor",
    "fulfillment",
    "capital",
    "compliance",
    "portfolio",
    "app"
)

rootProject.children.forEach { project ->
    project.projectDir = File(rootDir, "modules/${project.name}")
}
