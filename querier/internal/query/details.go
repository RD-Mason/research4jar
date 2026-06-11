package query

import (
	"context"
	"database/sql"
	"encoding/json"
	"fmt"

	"dev.springdep/querier/internal/project"
)

type AnnotationDetail struct {
	FQN        string          `json:"fqn"`
	Attributes json.RawMessage `json:"attributes"`
}

type MethodDetail struct {
	Name        string             `json:"name"`
	Descriptor  string             `json:"descriptor"`
	ReturnFQN   *string            `json:"return"`
	Modifiers   int                `json:"modifiers"`
	Annotations []AnnotationDetail `json:"annotations"`
}

type ConditionDetail struct {
	Target   string          `json:"target"`
	Type     string          `json:"type"`
	RefValue json.RawMessage `json:"ref_value"`
}

type BeanDetail struct {
	BeanName    string            `json:"bean_name"`
	BeanTypeFQN *string           `json:"bean_type"`
	ConfigFQN   string            `json:"config_class"`
	Method      *string           `json:"method"`
	Conditions  []ConditionDetail `json:"conditions"`
	SourceJar   string            `json:"source_jar"`
}

type ClassDetail struct {
	FQN         string             `json:"fqn"`
	Kind        *string            `json:"kind"`
	SuperFQN    *string            `json:"super"`
	Interfaces  []string           `json:"interfaces"`
	Modifiers   int                `json:"modifiers"`
	IsAbstract  bool               `json:"is_abstract"`
	SourceFile  *string            `json:"source_file"`
	SourceJar   string             `json:"source_jar"`
	Annotations []AnnotationDetail `json:"annotations"`
	Methods     []MethodDetail     `json:"methods"`
	Beans       []BeanDetail       `json:"bean_definitions"`
	Conditions  []ConditionDetail  `json:"conditions"`
}

type ClassResponse struct {
	Query    SymbolRequest `json:"query"`
	Results  []ClassDetail `json:"results"`
	Total    int           `json:"total"`
	Coverage Coverage      `json:"coverage"`
}

type BeanDefinitionsResponse struct {
	Query    SymbolRequest `json:"query"`
	Results  []BeanDetail  `json:"results"`
	Total    int           `json:"total"`
	Page     int           `json:"page"`
	PageSize int           `json:"page_size"`
	Coverage Coverage      `json:"coverage"`
}

type ConditionalTarget struct {
	FQN             string            `json:"fqn"`
	SourceJar       string            `json:"source_jar"`
	ClassConditions []ConditionDetail `json:"class_conditions"`
	BeanMethods     []BeanDetail      `json:"bean_methods"`
}

type ConditionalResponse struct {
	Query    SymbolRequest       `json:"query"`
	Results  []ConditionalTarget `json:"results"`
	Total    int                 `json:"total"`
	Coverage Coverage            `json:"coverage"`
}

// GetClass returns the full stored facts for every class row matching the FQN
// (one per shard that contains it). Detail commands are not paginated.
func GetClass(
	ctx context.Context,
	pointer project.Pointer,
	manifestPath string,
	fqn string,
) (ClassResponse, error) {
	session, err := openReadOnly(pointer.SessionDBPath, true)
	if err != nil {
		return ClassResponse{}, fmt.Errorf("open session database: %w", err)
	}
	defer session.Close()

	rows, err := session.QueryContext(
		ctx,
		`SELECT id, fqn, kind, super_fqn, modifiers, is_abstract, source_file, source_shard_id
		 FROM classes WHERE fqn = ?
		 ORDER BY source_shard_id`,
		fqn,
	)
	if err != nil {
		return ClassResponse{}, fmt.Errorf("query class: %w", err)
	}
	type classRow struct {
		id      int
		detail  ClassDetail
		shardID string
	}
	classRows := make([]classRow, 0, 1)
	for rows.Next() {
		var row classRow
		var kind, super, sourceFile sql.NullString
		var isAbstract sql.NullInt64
		if err := rows.Scan(
			&row.id, &row.detail.FQN, &kind, &super,
			&row.detail.Modifiers, &isAbstract, &sourceFile, &row.shardID,
		); err != nil {
			rows.Close()
			return ClassResponse{}, fmt.Errorf("scan class: %w", err)
		}
		row.detail.Kind = nullableString(kind)
		row.detail.SuperFQN = nullableString(super)
		row.detail.SourceFile = nullableString(sourceFile)
		row.detail.IsAbstract = isAbstract.Valid && isAbstract.Int64 != 0
		classRows = append(classRows, row)
	}
	if err := rows.Err(); err != nil {
		rows.Close()
		return ClassResponse{}, fmt.Errorf("iterate classes: %w", err)
	}
	rows.Close()

	var sources map[string]string
	if len(classRows) > 0 {
		if sources, err = loadSourceJars(ctx, manifestPath); err != nil {
			return ClassResponse{}, err
		}
	}

	results := make([]ClassDetail, 0, len(classRows))
	for _, row := range classRows {
		detail := row.detail
		detail.SourceJar = sourceJarName(sources, row.shardID)
		if detail.Interfaces, err = classInterfaces(ctx, session, row.id); err != nil {
			return ClassResponse{}, err
		}
		if detail.Annotations, err = targetAnnotations(ctx, session, "class", row.id); err != nil {
			return ClassResponse{}, err
		}
		if detail.Methods, err = classMethods(ctx, session, row.id); err != nil {
			return ClassResponse{}, err
		}
		if detail.Conditions, err = targetConditions(ctx, session, "class", row.id, "class"); err != nil {
			return ClassResponse{}, err
		}
		if detail.Beans, err = beansForConfig(ctx, session, sources, detail.FQN, row.shardID); err != nil {
			return ClassResponse{}, err
		}
		results = append(results, detail)
	}

	return ClassResponse{
		Query:    SymbolRequest{Command: "get-class", Arg: fqn},
		Results:  results,
		Total:    len(results),
		Coverage: coverageFrom(pointer),
	}, nil
}

// GetBeanDefinitions lists @Bean registrations whose bean type or declaring
// configuration class matches the FQN.
func GetBeanDefinitions(
	ctx context.Context,
	pointer project.Pointer,
	manifestPath string,
	fqn string,
	page int,
	pageSize int,
) (BeanDefinitionsResponse, error) {
	session, err := openReadOnly(pointer.SessionDBPath, true)
	if err != nil {
		return BeanDefinitionsResponse{}, fmt.Errorf("open session database: %w", err)
	}
	defer session.Close()

	var total int
	if err := session.QueryRowContext(
		ctx,
		`SELECT COUNT(*) FROM bean_definitions WHERE bean_type_fqn = ? OR config_fqn = ?`,
		fqn, fqn,
	).Scan(&total); err != nil {
		return BeanDefinitionsResponse{}, fmt.Errorf("count bean definitions: %w", err)
	}

	rows, err := session.QueryContext(
		ctx,
		`SELECT b.bean_name, b.bean_type_fqn, b.config_fqn, b.method_id,
		        m.name, m.descriptor, b.source_shard_id
		 FROM bean_definitions b
		 LEFT JOIN methods m ON m.id = b.method_id
		 WHERE b.bean_type_fqn = ? OR b.config_fqn = ?
		 ORDER BY b.config_fqn, b.bean_name, b.source_shard_id, b.id
		 LIMIT ? OFFSET ?`,
		fqn, fqn, pageSize, (page-1)*pageSize,
	)
	if err != nil {
		return BeanDefinitionsResponse{}, fmt.Errorf("query bean definitions: %w", err)
	}
	beans, err := scanBeans(ctx, session, rows)
	if err != nil {
		return BeanDefinitionsResponse{}, err
	}

	var sources map[string]string
	if len(beans) > 0 {
		if sources, err = loadSourceJars(ctx, manifestPath); err != nil {
			return BeanDefinitionsResponse{}, err
		}
	}
	for index := range beans {
		beans[index].SourceJar = sourceJarName(sources, beans[index].SourceJar)
	}

	return BeanDefinitionsResponse{
		Query:    SymbolRequest{Command: "get-bean-definitions", Arg: fqn},
		Results:  beans,
		Total:    total,
		Page:     page,
		PageSize: pageSize,
		Coverage: coverageFrom(pointer),
	}, nil
}

// ExplainConditional reports class-level and @Bean-method conditions for every
// class row matching the FQN.
func ExplainConditional(
	ctx context.Context,
	pointer project.Pointer,
	manifestPath string,
	fqn string,
) (ConditionalResponse, error) {
	session, err := openReadOnly(pointer.SessionDBPath, true)
	if err != nil {
		return ConditionalResponse{}, fmt.Errorf("open session database: %w", err)
	}
	defer session.Close()

	rows, err := session.QueryContext(
		ctx,
		`SELECT id, source_shard_id FROM classes WHERE fqn = ? ORDER BY source_shard_id`,
		fqn,
	)
	if err != nil {
		return ConditionalResponse{}, fmt.Errorf("query class: %w", err)
	}
	type classRef struct {
		id      int
		shardID string
	}
	refs := make([]classRef, 0, 1)
	for rows.Next() {
		var ref classRef
		if err := rows.Scan(&ref.id, &ref.shardID); err != nil {
			rows.Close()
			return ConditionalResponse{}, fmt.Errorf("scan class: %w", err)
		}
		refs = append(refs, ref)
	}
	if err := rows.Err(); err != nil {
		rows.Close()
		return ConditionalResponse{}, fmt.Errorf("iterate classes: %w", err)
	}
	rows.Close()

	var sources map[string]string
	if len(refs) > 0 {
		if sources, err = loadSourceJars(ctx, manifestPath); err != nil {
			return ConditionalResponse{}, err
		}
	}

	results := make([]ConditionalTarget, 0, len(refs))
	for _, ref := range refs {
		target := ConditionalTarget{
			FQN:       fqn,
			SourceJar: sourceJarName(sources, ref.shardID),
		}
		if target.ClassConditions, err = targetConditions(ctx, session, "class", ref.id, "class"); err != nil {
			return ConditionalResponse{}, err
		}
		if target.BeanMethods, err = beansForConfig(ctx, session, sources, fqn, ref.shardID); err != nil {
			return ConditionalResponse{}, err
		}
		results = append(results, target)
	}

	return ConditionalResponse{
		Query:    SymbolRequest{Command: "explain-conditional", Arg: fqn},
		Results:  results,
		Total:    len(results),
		Coverage: coverageFrom(pointer),
	}, nil
}

func classInterfaces(ctx context.Context, session *sql.DB, classID int) ([]string, error) {
	rows, err := session.QueryContext(
		ctx,
		`SELECT interface_fqn FROM class_interfaces WHERE class_id = ? ORDER BY interface_fqn`,
		classID,
	)
	if err != nil {
		return nil, fmt.Errorf("query interfaces: %w", err)
	}
	defer rows.Close()
	interfaces := []string{}
	for rows.Next() {
		var fqn string
		if err := rows.Scan(&fqn); err != nil {
			return nil, fmt.Errorf("scan interface: %w", err)
		}
		interfaces = append(interfaces, fqn)
	}
	return interfaces, rows.Err()
}

func targetAnnotations(
	ctx context.Context,
	session *sql.DB,
	targetKind string,
	targetID int,
) ([]AnnotationDetail, error) {
	rows, err := session.QueryContext(
		ctx,
		`SELECT annotation_fqn, attributes FROM annotations
		 WHERE target_kind = ? AND target_id = ?
		 ORDER BY annotation_fqn, COALESCE(attributes, '')`,
		targetKind, targetID,
	)
	if err != nil {
		return nil, fmt.Errorf("query annotations: %w", err)
	}
	defer rows.Close()
	annotations := []AnnotationDetail{}
	for rows.Next() {
		var detail AnnotationDetail
		var attributes sql.NullString
		if err := rows.Scan(&detail.FQN, &attributes); err != nil {
			return nil, fmt.Errorf("scan annotation: %w", err)
		}
		if attributes.Valid && json.Valid([]byte(attributes.String)) {
			detail.Attributes = json.RawMessage(attributes.String)
		}
		annotations = append(annotations, detail)
	}
	return annotations, rows.Err()
}

func targetConditions(
	ctx context.Context,
	session *sql.DB,
	targetKind string,
	targetID int,
	label string,
) ([]ConditionDetail, error) {
	rows, err := session.QueryContext(
		ctx,
		`SELECT type, ref_value FROM conditions
		 WHERE target_kind = ? AND target_id = ?
		 ORDER BY type, COALESCE(ref_value, '')`,
		targetKind, targetID,
	)
	if err != nil {
		return nil, fmt.Errorf("query conditions: %w", err)
	}
	defer rows.Close()
	conditions := []ConditionDetail{}
	for rows.Next() {
		detail := ConditionDetail{Target: label}
		var refValue sql.NullString
		if err := rows.Scan(&detail.Type, &refValue); err != nil {
			return nil, fmt.Errorf("scan condition: %w", err)
		}
		if refValue.Valid && json.Valid([]byte(refValue.String)) {
			detail.RefValue = json.RawMessage(refValue.String)
		}
		conditions = append(conditions, detail)
	}
	return conditions, rows.Err()
}

func classMethods(ctx context.Context, session *sql.DB, classID int) ([]MethodDetail, error) {
	rows, err := session.QueryContext(
		ctx,
		`SELECT id, name, descriptor, return_fqn, modifiers FROM methods
		 WHERE class_id = ?
		 ORDER BY name, descriptor`,
		classID,
	)
	if err != nil {
		return nil, fmt.Errorf("query methods: %w", err)
	}
	type methodRow struct {
		id     int
		detail MethodDetail
	}
	methodRows := []methodRow{}
	for rows.Next() {
		var row methodRow
		var returnFQN sql.NullString
		if err := rows.Scan(
			&row.id, &row.detail.Name, &row.detail.Descriptor, &returnFQN, &row.detail.Modifiers,
		); err != nil {
			rows.Close()
			return nil, fmt.Errorf("scan method: %w", err)
		}
		row.detail.ReturnFQN = nullableString(returnFQN)
		methodRows = append(methodRows, row)
	}
	if err := rows.Err(); err != nil {
		rows.Close()
		return nil, fmt.Errorf("iterate methods: %w", err)
	}
	rows.Close()

	methods := make([]MethodDetail, 0, len(methodRows))
	for _, row := range methodRows {
		annotations, err := targetAnnotations(ctx, session, "method", row.id)
		if err != nil {
			return nil, err
		}
		row.detail.Annotations = annotations
		methods = append(methods, row.detail)
	}
	return methods, nil
}

func beansForConfig(
	ctx context.Context,
	session *sql.DB,
	sources map[string]string,
	configFQN string,
	shardID string,
) ([]BeanDetail, error) {
	rows, err := session.QueryContext(
		ctx,
		`SELECT b.bean_name, b.bean_type_fqn, b.config_fqn, b.method_id,
		        m.name, m.descriptor, b.source_shard_id
		 FROM bean_definitions b
		 LEFT JOIN methods m ON m.id = b.method_id
		 WHERE b.config_fqn = ? AND b.source_shard_id = ?
		 ORDER BY b.bean_name, b.id`,
		configFQN, shardID,
	)
	if err != nil {
		return nil, fmt.Errorf("query bean definitions: %w", err)
	}
	beans, err := scanBeans(ctx, session, rows)
	if err != nil {
		return nil, err
	}
	for index := range beans {
		beans[index].SourceJar = sourceJarName(sources, beans[index].SourceJar)
	}
	return beans, nil
}

// scanBeans consumes bean rows (with SourceJar temporarily holding the shard
// id) and attaches each bean method's conditions.
func scanBeans(ctx context.Context, session *sql.DB, rows *sql.Rows) ([]BeanDetail, error) {
	type beanRow struct {
		detail   BeanDetail
		methodID sql.NullInt64
	}
	beanRows := []beanRow{}
	for rows.Next() {
		var row beanRow
		var beanType, methodName, methodDescriptor sql.NullString
		if err := rows.Scan(
			&row.detail.BeanName, &beanType, &row.detail.ConfigFQN,
			&row.methodID, &methodName, &methodDescriptor, &row.detail.SourceJar,
		); err != nil {
			rows.Close()
			return nil, fmt.Errorf("scan bean definition: %w", err)
		}
		row.detail.BeanTypeFQN = nullableString(beanType)
		if methodName.Valid && methodDescriptor.Valid {
			signature := methodName.String + methodDescriptor.String
			row.detail.Method = &signature
		}
		beanRows = append(beanRows, row)
	}
	if err := rows.Err(); err != nil {
		rows.Close()
		return nil, fmt.Errorf("iterate bean definitions: %w", err)
	}
	rows.Close()

	beans := make([]BeanDetail, 0, len(beanRows))
	for _, row := range beanRows {
		row.detail.Conditions = []ConditionDetail{}
		if row.methodID.Valid {
			conditions, err := targetConditions(
				ctx, session, "bean_method", int(row.methodID.Int64), "bean_method",
			)
			if err != nil {
				return nil, err
			}
			row.detail.Conditions = conditions
		}
		beans = append(beans, row.detail)
	}
	return beans, nil
}

func sourceJarName(sources map[string]string, shardID string) string {
	if name := sources[shardID]; name != "" {
		return name
	}
	return shardID
}
