plugin-build:
	./amper build --module kargo-intellij-plugin && python3 ./kargo-intellij-plugin/scripts/package-plugin.py