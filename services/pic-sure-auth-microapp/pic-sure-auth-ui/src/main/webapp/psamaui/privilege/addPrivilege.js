define(["backbone", "handlebars", "picSure/privilegeFunctions", "privilege/privilegeManagement", "text!privilege/addPrivilege.hbs"],
		function(BB, HBS, privilegeFunctions, privilegeManagement, template){
	var view = BB.View.extend({
		initialize: function(opts){
			this.applications = opts.applications;
			this.template = HBS.compile(template);
			this.managementConsole = opts.managementConsole;


		},
		events: {
			"click #save-privilege-button": "createPrivilege",
            "change #application-dropdown":"dropdownChange"
		},
		createPrivilege: function(event){
			var pName = $('#new-privilege-form input[name=privilege_name]').val();
			if(!pName || pName.length<=0){
                return;
			}

			var metadata = {};

			var privilege = {
				uuid : $('#new-privilege-form input[name=privilege_name]').attr('uuid'),
				name : pName,
				description : $('#new-privilege-form input[name=privilege_description]').val()
			};

			var applicationId = $('.application-block #uuid')[0].innerHTML;

			privilege.application = {uuid:applicationId};

			privilegeFunctions.createOrUpdatePrivilege(
				[privilege],
				"POST",
				function(result){
					console.log(result);
                    this.managementConsole.render();
				}.bind(this)
			);
		},
        dropdownChange: function(event){
            var selects = $('#application-dropdown option:selected', this.$el);
            $('.application-block #uuid').text(selects[0].value);
            if (selects[0].value){
                $('.application-block #name').text(selects[0].innerText);
                $('.application-block #description').text(selects[0].attributes.description.value);
            } else {
                $('.application-block #name').text("");
                $('.application-block #description').text("");
            }

        },
		render: function(){
			this.$el.html(this.template({applications:this.applications}));
			// this.renderConnectionForm({target:{value:this.connections[0].id}})
		}
	});
	return view;
});