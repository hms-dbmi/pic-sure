define(["backbone", "handlebars", "picSure/accessRuleFunctions", "accessRule/accessRuleManagement", "text!accessRule/addAccessRule.hbs"],
		function(BB, HBS, accessRuleFunctions, accessRuleManagement, template){
	var view = BB.View.extend({
		initialize: function(opts){
			this.accessRules = opts.accessRules;
			this.template = HBS.compile(template);
			this.managementConsole = opts.managementConsole;


		},
		events: {
			"click #save-accessRule-button": "createAccessRule"
		},
		createAccessRule: function(event){
			var metadata = {};

			var accessRule = {
				uuid : $('#new-accessRule-form input[name=accessRule_name]').attr('uuid'),
				name : $('#new-accessRule-form input[name=accessRule_name]').val(),
				description : $('#new-accessRule-form input[name=accessRule_description]').val()
			};
			accessRuleFunctions.createOrUpdateAccessRule(
				[accessRule],
				"POST",
				function(result){
					console.log(result);
                    this.managementConsole.render();
				}.bind(this)
			);
		},
		render: function(){
			this.$el.html(this.template({accessRules:this.accessRules}));
			// this.renderConnectionForm({target:{value:this.connections[0].id}})
		}
	});
	return view;
});