test:
	clojure -M:dev -m kaocha.runner

powerpack.jar: src/powerpack/*
	rm -f powerpack.jar && clojure -A:dev -M:jar

deploy: powerpack.jar
	mvn deploy:deploy-file -Dfile=powerpack.jar -DrepositoryId=clojars -Durl=https://clojars.org/repo -DpomFile=pom.xml

.PHONY: test deploy
