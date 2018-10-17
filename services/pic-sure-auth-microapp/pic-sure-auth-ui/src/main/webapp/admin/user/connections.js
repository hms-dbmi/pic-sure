define([], function(){
	// TODO : This will be integrated with a backend service when one exists.
    var defEmailField;
	var connections =  [
		{
			label:"BCH", 
			id:"ldap-connector",
            emailField: defEmailField = "BCHEmail",
			subPrefix:"ldap-connector|", 
			requiredFields:[{label:"BCH Email", id:defEmailField}],
			optionalFields:[{label:"BCH ID", id:"BCHId"}]
		}
		,{
			label:"HMS",
			id:"hms-it",
            emailField: defEmailField = "HMSEmail",
			subPrefix:"samlp|",
			requiredFields:[{label:"HMS Email", id:defEmailField}]
		}
	];
	return connections;
});