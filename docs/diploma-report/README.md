# Сборка PDF отчёта (LaTeX)

Папка отчёта: `docs/diploma-report`.

Основной входной файл:

- `main.tex`

Итоговый файл:

- `main.pdf`

## Флоу генерации PDF

1. Отредактировать файлы отчёта в `docs/diploma-report`.
2. Обновить нужные главы в `contents/*.tex`, библиографию в `main.bib`, изображения в `inc/`, при необходимости `main.tex`.
3. Запустить сборку одним из способов ниже.
4. Проверить результат в `docs/diploma-report/main.pdf`.
5. Если ссылки, оглавление или библиография обновились некорректно, просто запустить сборку ещё раз.

## Сборка через Docker

Рекомендуемый способ, потому что не требует локально установленного TeX.

### Linux / mac

```bash
docker run --rm -v "${PWD}/docs/diploma-report:/doc" -w /doc diploma-latex-builder:latest latexmk -xelatex -interaction=nonstopmode main.tex
```

### Windows

```powershell
docker run --rm -v "${PWD}\docs\diploma-report:/doc" -w /doc diploma-latex-builder:latest latexmk -xelatex -interaction=nonstopmode main.tex
```

Или с использованием переменной окружения:

```powershell
$projectRoot = (Get-Location).Path
docker run --rm -v "$projectRoot\docs\diploma-report:/doc" -w /doc diploma-latex-builder:latest latexmk -xelatex -interaction=nonstopmode main.tex
```

После завершения итоговый PDF будет здесь:

- `docs/diploma-report/main.pdf`

## Локальная сборка

Если `latexmk` и `xelatex` уже установлены:

```bash
cd docs/diploma-report
latexmk -xelatex -interaction=nonstopmode main.tex
```

## Вариант без команд (Overleaf)
Можно обойтись без локальных команд и собирать PDF прямо в Overleaf.

Флоу простй:

1. Открыть проект в Overleaf.
2. Загрузить туда содержимое папки docs/diploma-report
3. Убедиться, что главным файлом выбран main.tex.
4. Нажать Recompile.

## Очистка временных файлов

В папке `docs/diploma-report`:

```bash
latexmk -C
```

Команда удалит промежуточные артефакты сборки. `main.pdf` тоже может быть удалён в зависимости от настроек `latexmk`.
